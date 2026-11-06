package io.github.soclear.oneuix.hook.xmc

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * Entry point for XMorphClock hooks in the SystemUI process.
 *
 * The first construction step deliberately registers no adapter and changes
 * no SystemUI views. Samsung's One UI 9 clock targets must be identified and
 * validated before an adapter is added here.
 */
internal object XMorphClockModule {
    private const val TAG = "OneUIX-XMorphClock"

    // Adapters are added here after their target classes have been verified.
    private val adapters: List<LockscreenClockAdapter> = emptyList()

    @Volatile
    private var initialized = false

    @Synchronized
    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (initialized) return
        initialized = true

        if (adapters.isEmpty()) {
            module.log(
                Log.INFO,
                TAG,
                "XMorphClock scaffold loaded; no Samsung lock-screen adapter is registered."
            )
            return
        }

        adapters.forEach { adapter ->
            try {
                if (!adapter.isSupported(classLoader)) {
                    module.log(Log.WARN, TAG, "Adapter not supported: ${adapter.name}")
                    return@forEach
                }

                adapter.install(module, classLoader)
                module.log(Log.INFO, TAG, "Installed adapter: ${adapter.name}")
            } catch (throwable: Throwable) {
                module.log(Log.ERROR, TAG, "Adapter failed: ${adapter.name}", throwable)
            }
        }
    }
}
