package io.github.soclear.oneuix.hook

import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.hook.util.xlog

/** Samsung Capture 6.2.44.29 records in the package's :screenrecorder process. */
object SmartCapture {
    context(xposedModule: XposedModule)
    fun hookRecording(classLoader: ClassLoader) {
        runCatching {
            val methods = classLoader.loadClass("android.media.projection.MediaProjection")
                .declaredMethods.filter {
                    SecureRecordingCompatibility.isMediaProjectionCreation(
                        it.name, it.parameterTypes.map { parameter -> parameter.name }
                    )
                }
            check(methods.isNotEmpty()) { "No supported MediaProjection.createVirtualDisplay overload" }
            methods.forEach { method ->
                xposedModule.hook(method).intercept { chain ->
                    if (chain.args.firstOrNull() != "ScreenRecorder") return@intercept chain.proceed()
                    // Keep client flags unchanged. Requesting SECURE here can fail
                    // CAPTURE_SECURE_VIDEO_OUTPUT permission checks in the server.
                    // DisableFlagSecure adds the bit after those checks instead.
                    val result = try {
                        chain.proceed()
                    } catch (t: Throwable) {
                        xlog("Smart Capture: recording display creation failed", t)
                        throw t
                    }
                    runCatching {
                        val display = (result as? VirtualDisplay)?.display
                        val secure = display?.flags?.let { it and Display.FLAG_SECURE != 0 }
                        xlog(
                            "Smart Capture: ScreenRecorder display created=${display != null}, secure=$secure",
                            priority = Log.INFO
                        )
                    }.onFailure { xlog("Smart Capture: display inspection unavailable", it, Log.WARN) }
                    result
                }
            }
            xlog("Smart Capture: screen recorder hook installed", priority = Log.INFO)
        }.onFailure { xlog("Smart Capture: screen recorder hook unavailable", it, Log.WARN) }
    }
}
