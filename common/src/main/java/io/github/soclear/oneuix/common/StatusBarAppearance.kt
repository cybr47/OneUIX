package io.github.soclear.oneuix.common

/** Presentation only: never changes the battery state reported by Android. */
object StatusBarAppearance {
    fun batteryLabel(level: Int, charging: Boolean, hidePercent: Boolean, hideCharging: Boolean): String =
        buildString {
            append(level.coerceIn(0, 100))
            if (!hidePercent) append('%')
            if (charging && !hideCharging) append("\u26A1\uFE0E")
        }

    fun isHomeAppearanceActive(
        homeTask: Boolean,
        interactive: Boolean,
        keyguardLocked: Boolean,
        statusBarState: Int?,
        expanded: Boolean,
        dozing: Boolean,
    ): Boolean = homeTask && interactive && !keyguardLocked &&
        statusBarState == 0 && !expanded && !dozing
}
