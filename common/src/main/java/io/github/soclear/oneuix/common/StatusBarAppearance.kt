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

    /** Lock screen status bar parts that are located by view id or class name. */
    enum class LockscreenPart { CARRIER, NOTIFICATION_ICONS, CONNECTIVITY_ICONS, BATTERY }

    /**
     * Classify a view of the lock screen status bar from its resource entry name and class.
     * Matching is by name fragment, not exact class, because Samsung wraps or replaces these views
     * (for example the Compose battery). Containers that merely hold them return null.
     */
    fun lockscreenPart(idName: String?, className: String): LockscreenPart? {
        val id = idName.orEmpty().lowercase()
        val type = className.substringAfterLast('.').lowercase()
        fun has(vararg keys: String) = keys.any { id.contains(it) || type.contains(it) }
        return when {
            has("carrier", "operator") -> LockscreenPart.CARRIER
            has("notification_icon", "notificationicon") -> LockscreenPart.NOTIFICATION_ICONS
            has("battery") -> LockscreenPart.BATTERY
            has("statusicon", "status_icon") -> LockscreenPart.CONNECTIVITY_ICONS
            else -> null
        }
    }

    /**
     * Surfaces where the "Fade clock" option hides the clock: Home and Recents, never the app drawer.
     * The launcher reports `true` for the drawer (and Recents), `false` for Home. When it has not
     * reported yet (null) a launcher task is treated as Home, which was the behaviour before the
     * launcher state was consulted.
     */
    fun isClockSurface(homeTask: Boolean, recentsTask: Boolean, nativeLauncherAppearance: Boolean?): Boolean =
        recentsTask || (homeTask && nativeLauncherAppearance != true)

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
