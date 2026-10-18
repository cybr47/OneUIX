package io.github.soclear.oneuix.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarWindowHandoffTest {
    @Test fun drawerKeepsStockTintButDoesNotDisableRecentsOrReturningHome() {
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false)) // Home
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false)) // Drawer
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(true, true)) // Recents over drawer
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false)) // Back to drawer
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false)) // Back Home
    }

    @Test fun systemUiRestartKeepsNativeHomeTintUntilLauncherReplies() {
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(null, false))
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false))
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false))
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(null, true))
    }

    @Test fun appWindowWinsBeforeTheHomeTaskCallbackArrives() {
        val staleHomeTask = StatusBarAppearance.isHomeAppearanceActive(true, true, false, 0, false, false)
        assertTrue(staleHomeTask)
        for (recents in listOf(false, true)) {
            assertFalse(staleHomeTask && StatusBarAppearance.windowAllowsAdaptiveTint("com.android.settings", recents))
            assertFalse(staleHomeTask && StatusBarAppearance.windowAllowsAdaptiveTint("com.apple.android.music", recents))
            // Even a late Home reply cannot take ownership back from the app window.
            assertFalse(staleHomeTask && StatusBarAppearance.launcherAllowsAdaptiveTint(false, recents) &&
                StatusBarAppearance.windowAllowsAdaptiveTint("com.android.settings", recents))
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
