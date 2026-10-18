package io.github.soclear.oneuix.common

/** Presentation only: never changes the battery state reported by Android. */
object StatusBarAppearance {
    fun batteryLabel(level: Int, charging: Boolean, hidePercent: Boolean, hideCharging: Boolean): String =
        buildString {
            append(level.coerceIn(0, 100))
            if (!hidePercent) append('%')
            if (charging && !hideCharging) append("\u26A1\uFE0E")
        }

    /** Detect Android's Recents activity type, with a launcher-class fallback. */
    fun isRecentsTask(activityType: Int?, packageName: String?, className: String?): Boolean =
        activityType == 3 ||
            (packageName == "com.sec.android.app.launcher" &&
                (className?.contains("recents", ignoreCase = true) == true ||
                    className?.contains("overview", ignoreCase = true) == true))

    /**
     * A task/layout refresh must not re-dispatch identical tint values to Compose.
     * That can produce a momentary status icon redraw during app transitions.
     */
    fun needsTintUpdate(
        alreadyOverridden: Boolean,
        previousIntensity: Float?,
        desiredIntensity: Float,
        sameAreas: Boolean,
    ): Boolean = !alreadyOverridden || previousIntensity != desiredIntensity || !sameAreas

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
