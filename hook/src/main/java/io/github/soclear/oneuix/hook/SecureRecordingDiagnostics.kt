package io.github.soclear.oneuix.hook

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.hook.util.xlog
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * DEBUG BRANCH ONLY. Log-only hooks (they never change behaviour) that record what Samsung's
 * system server and Smart Capture do during a screen recording. Search the module log for "[DIAG]".
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi")
object SecureRecordingDiagnostics {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    private fun allow(key: String, max: Int): Boolean =
        counters.getOrPut(key) { AtomicInteger() }.incrementAndGet() <= max

    context(xposedModule: XposedModule)
    private fun diag(message: String) = xlog("[DIAG] $message", priority = Log.INFO)

    private fun describe(arg: Any?): String = when (arg) {
        null -> "null"
        is Int, is Long, is Boolean, is Float, is Double -> arg.toString()
        is String -> "\"" + arg.take(80) + "\""
        else -> {
            val name = arg.javaClass.name
            if (name.endsWith("VirtualDisplayConfig")) {
                val config = runCatching {
                    val n = arg.javaClass.getMethod("getName").invoke(arg)
                    val f = arg.javaClass.getMethod("getFlags").invoke(arg) as Int
                    "name=$n flags=0x${f.toString(16)}"
                }.getOrDefault("?")
                "$name{$config}"
            } else name
        }
    }

    private fun callers(): String = Throwable().stackTrace
        .filterNot { it.className.startsWith("io.github.soclear") || it.className.startsWith("de.robv") ||
                it.className.startsWith("io.github.libxposed") || it.className.startsWith("LSPHooker") ||
                it.className.startsWith("java.lang.reflect") }
        .take(12).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }

    private fun simple(m: Method) = m.name + m.parameterTypes.joinToString(",", "(", ")") { it.simpleName }

    // ---------------------------------------------------------------- system_server

    context(xposedModule: XposedModule)
    fun hookSystemServer(classLoader: ClassLoader) {
        runCatching {
            diag("system_server up: ${Build.FINGERPRINT} sdk=${Build.VERSION.SDK_INT} " +
                    "oneui=${runCatching { Build.VERSION::class.java }.isSuccess}")
            scanMethods(classLoader)
            hookCreation(classLoader, "com.android.server.display.VirtualDisplayAdapter", "createVirtualDisplayLocked")
            hookCreation(classLoader, "com.android.server.display.DisplayManagerService",
                "createVirtualDisplayInternal", "createVirtualDisplayLocked")
            hookCreation(classLoader, "com.android.server.display.DisplayControl", "createVirtualDisplay", "createDisplay")
            hookProjectionPolicy(classLoader)
            hookSetSecure(classLoader)
        }.onFailure { xlog(it) }
    }

    /** Which secure/capture/record related methods does this firmware have? (Reveals Samsung additions.) */
    context(xposedModule: XposedModule)
    private fun scanMethods(classLoader: ClassLoader) {
        val pattern = Regex("secure|blackout|mirror|projection|record|capture|screenshot", RegexOption.IGNORE_CASE)
        listOf(
            "com.android.server.display.DisplayManagerService",
            "com.android.server.display.DisplayManagerService\$BinderService",
            "com.android.server.display.VirtualDisplayAdapter",
            "com.android.server.display.DisplayControl",
            "com.android.server.media.projection.MediaProjectionManagerService",
            "com.android.server.media.projection.MediaProjectionManagerService\$MediaProjection",
            "com.android.server.wm.WindowState",
            "com.android.server.wm.WindowStateAnimator",
            "com.android.server.wm.WindowManagerService",
            "com.android.server.wm.ContentRecorder",
            "com.android.server.wm.WmScreenshotController",
            "android.view.SurfaceControl\$Transaction",
        ).forEach { name ->
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull()
            if (clazz == null) {
                diag("scan: $name MISSING")
            } else {
                val methods = clazz.declaredMethods.filter { pattern.containsMatchIn(it.name) }
                    .map(::simple).sorted()
                diag("scan: $name (${clazz.declaredMethods.size} methods)\n" + methods.joinToString("\n"))
            }
        }
    }

    context(xposedModule: XposedModule)
    private fun hookCreation(classLoader: ClassLoader, className: String, vararg names: String) {
        val clazz = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return
        clazz.declaredMethods.filter { it.name in names }.forEach { method ->
            diag("hooking ${clazz.simpleName}.${simple(method)}")
            xposedModule.hook(method).intercept { chain ->
                val key = "create:" + clazz.simpleName + "." + method.name
                val log = allow(key, 60)
                if (log) diag("${clazz.simpleName}.${method.name}(" + chain.args.joinToString(", ") { describe(it) } +
                        ")\n  from " + callers())
                try {
                    val result = chain.proceed()
                    if (log) diag("${clazz.simpleName}.${method.name} -> ${result?.javaClass?.name}")
                    result
                } catch (t: Throwable) {
                    if (log) diag("${clazz.simpleName}.${method.name} threw $t")
                    throw t
                }
            }
        }
    }

    context(xposedModule: XposedModule)
    private fun hookProjectionPolicy(classLoader: ClassLoader) {
        val clazz = runCatching {
            classLoader.loadClass("com.android.server.media.projection.MediaProjectionManagerService\$MediaProjection")
        }.getOrNull() ?: return
        clazz.declaredMethods.filter {
            it.name == "canProjectSecureVideo" || it.name == "applyVirtualDisplayFlags" ||
                    it.name == "canProjectVideo" || it.name == "getType" || it.name == "isValid"
        }.forEach { method ->
            xposedModule.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (allow("proj:" + method.name, 25)) {
                    diag("MediaProjection.${simple(method)}(" + chain.args.joinToString { describe(it) } + ") = $result")
                }
                result
            }
        }
    }

    context(xposedModule: XposedModule)
    private fun hookSetSecure(classLoader: ClassLoader) {
        val clazz = runCatching { classLoader.loadClass("android.view.SurfaceControl\$Transaction") }.getOrNull() ?: return
        clazz.declaredMethods.filter { it.name == "setSecure" }.forEach { method ->
            xposedModule.hook(method).intercept { chain ->
                if (allow("setSecure", 150)) {
                    diag("Transaction.setSecure(layer=${chain.args.getOrNull(0)}, secure=${chain.args.getOrNull(1)})")
                }
                chain.proceed()
            }
        }
    }

    // ---------------------------------------------------------------- Smart Capture

    context(xposedModule: XposedModule)
    fun hookSmartCapture(classLoader: ClassLoader) {
        val base = "com.samsung.android.app.smartcapture"
        runCatching {
            diag("Smart Capture :screenrecorder process hooked")
            runCatching {
                classLoader.loadClass("$base.baseutil.image.ImageUtils").declaredMethods
                    .filter { it.name == "isEmpty" && it.parameterTypes.firstOrNull() == Bitmap::class.java }
                    .forEach { method ->
                        xposedModule.hook(method).intercept { chain ->
                            val result = chain.proceed()
                            val bmp = chain.args.firstOrNull() as? Bitmap
                            diag("start check ImageUtils.isEmpty -> $result (bitmap=${bmp?.width}x${bmp?.height})")
                            result
                        }
                    }
            }
            runCatching {
                classLoader.loadClass("$base.screenrecorder.ScreenRecorderController").declaredMethods
                    .filter { it.name == "checkPreConditions" }.forEach { method ->
                        xposedModule.hook(method).intercept { chain ->
                            diag("checkPreConditions start")
                            try {
                                chain.proceed().also { diag("checkPreConditions ok") }
                            } catch (t: Throwable) {
                                diag("checkPreConditions FAILED: $t")
                                throw t
                            }
                        }
                    }
            }
            runCatching {
                classLoader.loadClass("$base.screenrecorder.recorder.core.MP4Recorder").declaredMethods
                    .filter { it.name == "createVirtualDisplay" || it.name == "initVirtualDisplay" }.forEach { method ->
                        xposedModule.hook(method).intercept { chain ->
                            diag("MP4Recorder.${method.name} start")
                            val result = chain.proceed()
                            diag("MP4Recorder.${method.name} done")
                            result
                        }
                    }
            }
            runCatching {
                classLoader.loadClass("android.media.projection.MediaProjection").declaredMethods
                    .filter { it.name == "createVirtualDisplay" && it.parameterCount == 8 }.forEach { method ->
                        xposedModule.hook(method).intercept { chain ->
                            diag("MediaProjection.createVirtualDisplay(name=${chain.args[0]}, ${chain.args[1]}x" +
                                    "${chain.args[2]}, dpi=${chain.args[3]}, flags=0x${(chain.args[4] as Int).toString(16)})")
                            val result = chain.proceed()
                            val display = (result as? android.hardware.display.VirtualDisplay)?.display
                            diag("  -> displayId=${display?.displayId} flags=0x${display?.flags?.toString(16)} " +
                                    "secureCapable=${display?.flags?.let { it and android.view.Display.FLAG_SECURE != 0 }}")
                            result
                        }
                    }
            }
            runCatching {
                classLoader.loadClass("android.media.projection.MediaProjection\$Callback").declaredMethods
                    .filter { it.name == "onStop" }.forEach { method ->
                        xposedModule.hook(method).intercept { chain ->
                            diag("MediaProjection.Callback.onStop from " + callers())
                            chain.proceed()
                        }
                    }
            }
            runCatching {
                classLoader.loadClass("$base.screenrecorder.recorder.core.MP4Recorder\$2").declaredMethods
                    .filter { it.name == "onStop" }.forEach { method ->
                        xposedModule.hook(method).intercept { chain ->
                            diag("Smart Capture projection onStop (recording is being stopped)")
                            chain.proceed()
                        }
                    }
            }
        }.onFailure { xlog(it) }
    }
}
