package io.github.soclear.oneuix.common

import org.junit.Assert.*
import org.junit.Test

class StatusBarAppearanceTest {
    @Test fun oldBackupsKeepBothHomeOptionsOff() {
        val restored = IgnoreUnknownKeysJson.decodeFromString<Preference>(
            """{"systemUI":{"statusBar":{"hideBatteryIcon":true,"addBatteryLevelText":true}}}"""
        ).systemUI.statusBar
        assertFalse(restored.adaptiveHomeStatusBar)
        assertFalse(restored.hideClockOnHome)
        assertTrue(restored.hideBatteryIcon)
        assertTrue(restored.addBatteryLevelText)
    }

    @Test fun newOptionsAndAllBatteryOptionsPersistIndependently() {
        for (mask in 0 until 64) {
            fun enabled(bit: Int) = mask and (1 shl bit) != 0
            val status = Preference.SystemUI.StatusBar(
                adaptiveHomeStatusBar = enabled(0), hideClockOnHome = enabled(1),
                hideBatteryIcon = enabled(2), addBatteryLevelText = enabled(3),
                hideBatteryLevelTextPercentageSign = enabled(4),
                hideBatteryLevelTextChargingIcon = enabled(5)
            )
            val value = Preference(systemUI = Preference.SystemUI(statusBar = status))
            val json = IgnoreUnknownKeysJson.encodeToString(Preference.serializer(), value)
            assertEquals(status, IgnoreUnknownKeysJson.decodeFromString<Preference>(json).systemUI.statusBar)
        }
    }

    @Test fun batteryTextChargingAndPercentageCombinationMatrix() {
        val cases = listOf(
            Triple(false, false, false) to "96%",
            Triple(false, false, true) to "96%",
            Triple(false, true, false) to "96",
            Triple(false, true, true) to "96",
            Triple(true, false, false) to "96%\u26A1\uFE0E",
            Triple(true, false, true) to "96%",
            Triple(true, true, false) to "96\u26A1\uFE0E",
            Triple(true, true, true) to "96",
        )
        cases.forEach { (flags, text) ->
            assertEquals(text, StatusBarAppearance.batteryLabel(96, flags.first, flags.second, flags.third))
        }
    }

    @Test fun invalidBatteryLevelsCannotCreateOversizedLabels() {
        assertEquals("0%", StatusBarAppearance.batteryLabel(Int.MIN_VALUE, false, false, false))
        assertEquals("100%", StatusBarAppearance.batteryLabel(Int.MAX_VALUE, false, false, false))
    }

    @Test fun recentsDetectionDoesNotIncludeOrdinaryApplications() {
        assertTrue(StatusBarAppearance.isRecentsTask(3, "com.android.systemui", null))
        assertTrue(StatusBarAppearance.isRecentsTask(2, "com.sec.android.app.launcher", "com.samsung.RecentsActivity"))
        assertTrue(StatusBarAppearance.isRecentsTask(null, "com.sec.android.app.launcher", "OverviewActivity"))
        assertFalse(StatusBarAppearance.isRecentsTask(1, "com.android.vending", "com.example.RecentsActivity"))
        assertFalse(StatusBarAppearance.isRecentsTask(2, "com.sec.android.app.launcher", "com.sec.android.app.launcher.HomeActivity"))
        assertFalse(StatusBarAppearance.isRecentsTask(null, null, null))
        // Home tint still respects the regular unlocked/interactive safeguards.
        assertTrue(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, true, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, true, false))
    }

    @Test fun clockHidesOnHomeAndRecentsButNotInTheAppDrawer() {
        // Home: launcher reports native appearance == false.
        assertTrue(StatusBarAppearance.isClockSurface(true, false, false))
        // App drawer: launcher task, native appearance == true.
        assertFalse(StatusBarAppearance.isClockSurface(true, false, true))
        // Recents, whether or not the task still reports Home and whatever the launcher state says.
        assertTrue(StatusBarAppearance.isClockSurface(false, true, true))
        assertTrue(StatusBarAppearance.isClockSurface(true, true, true))
        assertTrue(StatusBarAppearance.isClockSurface(true, true, null))
        // Not a launcher surface at all (an ordinary app).
        assertFalse(StatusBarAppearance.isClockSurface(false, false, false))
        assertFalse(StatusBarAppearance.isClockSurface(false, false, null))
        // Launcher state not yet known: keep the earlier Home-task behaviour.
        assertTrue(StatusBarAppearance.isClockSurface(true, false, null))
    }

    @Test fun homeAppearanceRequiresUnlockedInteractiveHomeWithShadeClosed() {
        assertTrue(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(false, true, false, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, false, false, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, true, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 1, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 2, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, null, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, true, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, false, true))
    }
}
