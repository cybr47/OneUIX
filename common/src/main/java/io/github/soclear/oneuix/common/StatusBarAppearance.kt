package io.github.soclear.oneuix.common

/** Presentation only: never changes the battery state reported by Android. */
object StatusBarAppearance {
    fun batteryLabel(level: Int, charging: Boolean, hidePercent: Boolean, hideCharging: Boolean): String =
        buildString {
            append(level.coerceIn(0, 100))
            if (!hidePercent) append('%')
            if (charging && !hideCharging) append("\u26A1\uFE0E")
        }

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
}
