package io.github.soclear.oneuix.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarWindowHandoffTest {
    @Test fun appWindowWinsBeforeTheHomeTaskCallbackArrives() {
        val staleHomeTask = StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, false, false)
        assertTrue(staleHomeTask)
        for (recents in listOf(false, true)) {
            assertFalse(staleHomeTask && StatusBarAppearance.windowAllowsAdaptiveTint("com.android.settings", recents))
            assertFalse(staleHomeTask && StatusBarAppearance.windowAllowsAdaptiveTint("com.apple.android.music", recents))
        }
    }

    @Test fun aHomeTaskCannotSampleOverTheDepartingAppWindow() {
        assertFalse(StatusBarAppearance.windowAllowsAdaptiveTint("com.android.settings", false))
        assertTrue(StatusBarAppearance.windowAllowsAdaptiveTint("com.sec.android.app.launcher", false))
    }

    @Test fun launcherOverviewAndIdentifiedSystemUiRecentsRemainEligible() {
        assertTrue(StatusBarAppearance.windowAllowsAdaptiveTint("com.sec.android.app.launcher", true))
        assertTrue(StatusBarAppearance.windowAllowsAdaptiveTint("com.android.systemui", true))
        assertFalse(StatusBarAppearance.windowAllowsAdaptiveTint("com.android.systemui", false))
        assertFalse(StatusBarAppearance.windowAllowsAdaptiveTint("com.example.launcher", true))
    }

    @Test fun unknownWindowAtStartupStillRequiresTheNormalHomeGuards() {
        assertTrue(StatusBarAppearance.windowAllowsAdaptiveTint(null, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(false, true, false, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, true, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, true, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, false, false, 0, false, false))
        assertFalse(StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, false, true))
    }
}
