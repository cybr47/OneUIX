package io.github.soclear.oneuix.hook

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.SurfaceControl
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.xlog
import java.util.function.BiConsumer
import java.util.function.BiPredicate

// TODO: https://github.com/LSPosed/DisableFlagSecure/pull/115
@SuppressLint("PrivateApi", "BlockedPrivateApi")
object DisableFlagSecure {

    context(xposedModule: XposedModule)
    fun hookSystemServer(classLoader: ClassLoader) {
        deoptimizeSystemServer(classLoader)
        hookWindowState(classLoader)
        hookScreenCapture(classLoader)
        hookScreenshotHardwareBuffer(classLoader)
        hookOneUI(classLoader)
        hookSamsungRecordingDisplay(classLoader)
        hookVirtualDisplayAdapter(classLoader)
        hookDisplayControl(classLoader)
        hookDetection(classLoader)
        hookBlackoutPermission(classLoader)
    }

    context(xposedModule: XposedModule)
    fun hookSystemUI(classLoader: ClassLoader) {
        hookScreenshotHardwareBuffer(classLoader)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            hookScreenCapture(classLoader)
        }
    }

    context(xposedModule: XposedModule)
    private fun deoptimizeSystemServer(classLoader: ClassLoader) {
        fun deopt(clazz: Class<*>, vararg names: String) =
            clazz.declaredMethods.filter { it.name in names }.forEach { xposedModule.deoptimize(it) }

        runCatching {
            deopt(classLoader.loadClass("com.android.server.wm.WindowStateAnimator"), "createSurfaceLocked")
            deopt(classLoader.loadClass("com.android.server.wm.WindowManagerService"), "relayoutWindow")
            for (i in 0..19) {
                runCatching {
                    val clz =
                        classLoader.loadClass($$$"com.android.server.wm.RootWindowContainer$$ExternalSyntheticLambda$$$i")
                    if (BiConsumer::class.java.isAssignableFrom(clz)) deopt(clz, "accept")
                }
                runCatching {
                    val clz = classLoader.loadClass($$"com.android.server.wm.DisplayContent$$$i")
                    if (BiPredicate::class.java.isAssignableFrom(clz)) deopt(clz, "test")
                }
            }
        }
        // These callers may have an inlined copy of a display-creation method.
        // Keep this independent of the screenshot deoptimizations above.
        runCatching {
            deopt(
                classLoader.loadClass("com.android.server.display.DisplayManagerService"),
                "createVirtualDisplayInternal", "createVirtualDisplayLocked"
            )
            deopt(
                classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter"),
                "createVirtualDisplayLocked"
            )
        }.onFailure { xlog("Secure recording: display deoptimization unavailable", it, Log.WARN) }
    }

    context(xposedModule: XposedModule)
    private fun hookWindowState(classLoader: ClassLoader) = runCatching {
        val windowStateClass = classLoader.loadClass("com.android.server.wm.WindowState")
        val systemServerCl = windowStateClass.classLoader
        xposedModule.hook(windowStateClass.getDeclaredMethod("isSecureLocked")).intercept { chain ->
            val allow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk { frames ->
                    frames.anyMatch {
                        it.declaringClass?.classLoader == systemServerCl &&
                                (it.methodName == "setInitialSurfaceControlProperties" || it.methodName == "createSurfaceLocked")
                    }
                }
            } else {
                Throwable().stackTrace.any { frame ->
                    (frame.methodName == "setInitialSurfaceControlProperties" || frame.methodName == "createSurfaceLocked") &&
                            runCatching { classLoader.loadClass(frame.className).classLoader == systemServerCl }.getOrDefault(
                                false
                            )
                }
            }
            if (allow) chain.proceed() else false
        }
    }

    context(xposedModule: XposedModule)
    private fun hookScreenCapture(classLoader: ClassLoader) = runCatching {
        val (screenCaptureClass, captureArgsClass) = runCatching {
            classLoader.loadClass("android.window.ScreenCaptureInternal") to
                    classLoader.loadClass($$"android.window.ScreenCaptureInternal$CaptureArgs")
        }.recoverCatching {
            classLoader.loadClass("android.window.ScreenCapture") to
                    classLoader.loadClass($$"android.window.ScreenCapture$CaptureArgs")
        }.getOrElse {
            SurfaceControl::class.java to classLoader.loadClass($$"android.view.SurfaceControl$CaptureArgs")
        }
        val field = runCatching { captureArgsClass.getDeclaredField("mSecureContentPolicy") }
            .getOrElse { captureArgsClass.getDeclaredField("mCaptureSecureLayers") }
            .apply { isAccessible = true }
        val isPolicy = field.name == "mSecureContentPolicy"

        val hooker: (XposedInterface.Chain) -> Any? = { chain ->
            runCatching { field.set(chain.args[0], if (isPolicy) 1 else true) }
            chain.proceed()
        }
        screenCaptureClass.declaredMethods
            .filter { it.name == "nativeCaptureDisplay" || it.name == "nativeCaptureLayers" }
            .forEach { xposedModule.hook(it).intercept(hooker) }
    }

    context(xposedModule: XposedModule)
    private fun hookScreenshotHardwareBuffer(classLoader: ClassLoader) {
        listOf(
            $$"android.window.ScreenCaptureInternal$ScreenshotHardwareBuffer",
            $$"android.window.ScreenCapture$ScreenshotHardwareBuffer",
            $$"android.view.SurfaceControl$ScreenshotHardwareBuffer"
        ).forEach { className ->
            runCatching {
                classLoader.loadClass(className).declaredMethods
                    .filter { it.name == "containsSecureLayers" }
                    .forEach { xposedModule.hook(it).intercept { false } }
            }
        }
    }

    context(xposedModule: XposedModule)
    private fun hookOneUI(classLoader: ClassLoader) = runCatching {
        classLoader.loadClass("com.android.server.wm.WmScreenshotController").declaredMethods
            .filter { it.name == "canBeScreenshotTarget" || it.name == "isScreenshotAllowedByPolicy" }
            .forEach {
                xposedModule.hook(it).intercept { true }
            }
    }

    context(xposedModule: XposedModule)
    private fun hookSamsungRecordingDisplay(classLoader: ClassLoader) =
        hookVirtualDisplayFlags(classLoader, "com.android.server.display.DisplayManagerService", true)

    context(xposedModule: XposedModule)
    private fun hookVirtualDisplayAdapter(classLoader: ClassLoader) =
        hookVirtualDisplayFlags(classLoader, "com.android.server.display.VirtualDisplayAdapter", false)

    context(xposedModule: XposedModule)
    private fun hookVirtualDisplayFlags(
        classLoader: ClassLoader,
        className: String,
        smartCaptureOnly: Boolean
    ) = runCatching {
        val methods = classLoader.loadClass(className).declaredMethods.mapNotNull { method ->
            SecureRecordingCompatibility.virtualDisplayFlagsIndex(
                method.name, method.parameterTypes.map { it.name }
            )?.let { method to it }
        }
        check(methods.isNotEmpty()) { "No supported $className.createVirtualDisplayLocked overload" }
        methods.forEach { (method, flagsIndex) ->
            xposedModule.hook(method).intercept { chain ->
                val caller = chain.args.getOrNull(2) as? Int ?: return@intercept chain.proceed()
                val projection = chain.args.getOrNull(1)
                val isSmartCapture = chain.args.getOrNull(3) == Package.SMART_CAPTURE
                if (smartCaptureOnly) {
                    if (!isSmartCapture || projection == null) return@intercept chain.proceed()
                } else if (caller >= 10000 && projection == null) {
                    return@intercept chain.proceed()
                }
                val flags = chain.args.getOrNull(flagsIndex) as? Int
                    ?: return@intercept chain.proceed()
                val secureFlags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                if (smartCaptureOnly) {
                    xlog(
                        "Smart Capture: system display flags 0x${flags.toString(16)} -> 0x${secureFlags.toString(16)}",
                        priority = Log.INFO
                    )
                }
                if (secureFlags == flags) return@intercept chain.proceed()
                val newArgs = chain.args.toTypedArray()
                newArgs[flagsIndex] = secureFlags
                chain.proceed(newArgs)
            }
        }
        xlog("Secure recording: hooked $className (${methods.size})", priority = Log.INFO)
    }.onFailure { xlog("Secure recording: display flags hook unavailable for $className", it, Log.WARN) }

    context(xposedModule: XposedModule)
    private fun hookDisplayControl(classLoader: ClassLoader) = runCatching {
        val displayClass = runCatching {
            classLoader.loadClass("com.android.server.display.DisplayControl")
        }.getOrElse { SurfaceControl::class.java }
        val systemServerCl = displayClass.classLoader
        val methods = displayClass.declaredMethods
            .filter {
                SecureRecordingCompatibility.isDisplayControlCreation(
                    it.name, it.parameterTypes.map { parameter -> parameter.name }
                )
            }
        check(methods.isNotEmpty()) { "No supported DisplayControl display creation overload" }
        methods.forEach { method ->
            xposedModule.hook(method).intercept { chain ->
                // Extend the newer overload only for Samsung's recorder. Keep
                // the existing two-argument behavior for other display users.
                if (method.parameterCount == 4 && chain.args.firstOrNull() != "ScreenRecorder") {
                    return@intercept chain.proceed()
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val fromVDA = Throwable().stackTrace.any { frame ->
                        frame.methodName == "createVirtualDisplayLocked" &&
                                runCatching {
                                    classLoader.loadClass(frame.className).classLoader == systemServerCl
                                }.getOrDefault(
                                    false
                                )
                    }
                    if (fromVDA) return@intercept chain.proceed()
                }
                val newArgs = chain.args.toTypedArray()
                newArgs[1] = true
                chain.proceed(newArgs)
            }
        }
        xlog("Secure recording: hooked DisplayControl (${methods.size})", priority = Log.INFO)
    }.onFailure { xlog("Secure recording: DisplayControl hook unavailable", it, Log.WARN) }

    context(xposedModule: XposedModule)
    private fun hookDetection(classLoader: ClassLoader) {
        runCatching {
            classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService").declaredMethods
                .filter { it.name == "registerScreenCaptureObserver" }.forEach {
                    xposedModule.hook(it).intercept { null }
                }
        }
        runCatching {
            classLoader.loadClass("com.android.server.wm.WindowManagerService").declaredMethods
                .filter { it.name == "registerScreenRecordingCallback" }.forEach {
                    xposedModule.hook(it).intercept { false }
                }
        }
    }

    context(xposedModule: XposedModule)
    private fun hookBlackoutPermission(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            classLoader.loadClass("com.android.server.am.ActivityManagerService").declaredMethods
                .filter { it.name == "checkPermission" && it.parameterTypes.firstOrNull() == String::class.java }
                .forEach { method ->
                    xposedModule.hook(method).intercept { chain ->
                        if (chain.args.firstOrNull() == "android.permission.CAPTURE_BLACKOUT_CONTENT") {
                            val newArgs = chain.args.toTypedArray()
                            newArgs[0] = "android.permission.READ_FRAME_BUFFER"
                            chain.proceed(newArgs)
                        } else chain.proceed()
                    }
                }
        }
    }
}
