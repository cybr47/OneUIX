package io.github.soclear.oneuix.common

import org.junit.Assert.*
import org.junit.Test

class StatusBarAppearanceTest {
    @Test fun batteryOptionsPersistIndependently() {
        for (mask in 0 until 16) {
            fun enabled(bit: Int) = mask and (1 shl bit) != 0
            val status = Preference.SystemUI.StatusBar(
                hideBatteryIcon = enabled(0), addBatteryLevelText = enabled(1),
                hideBatteryLevelTextPercentageSign = enabled(2),
                hideBatteryLevelTextChargingIcon = enabled(3)
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
}
