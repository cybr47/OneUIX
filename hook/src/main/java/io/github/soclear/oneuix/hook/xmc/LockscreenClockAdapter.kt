package io.github.soclear.oneuix.hook.xmc

import io.github.libxposed.api.XposedModule

/**
 * Samsung-specific integration boundary for an XMorphClock host.
 *
 * Keep SystemUI class discovery and view attachment inside adapters. The face
 * renderer must remain independent of One UI implementation details.
 */
internal interface LockscreenClockAdapter {
    val name: String

    fun isSupported(classLoader: ClassLoader): Boolean

    fun install(module: XposedModule, classLoader: ClassLoader)
}
