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

    /** A native app-window event wins even while the task stack still reports Home. */
    fun windowAllowsAdaptiveTint(packageName: String?, recentsTask: Boolean): Boolean =
        !recentsTask && (packageName == null || packageName == "com.sec.android.app.launcher")

    /** Only Home is adaptive. Drawer, Recents and unknown launcher state retain native colours. */
    fun launcherAllowsAdaptiveTint(nativeLauncherAppearance: Boolean?, recentsTask: Boolean): Boolean =
        !recentsTask && nativeLauncherAppearance == false

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
