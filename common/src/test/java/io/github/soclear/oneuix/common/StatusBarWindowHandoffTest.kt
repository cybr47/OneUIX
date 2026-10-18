package io.github.soclear.oneuix.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarWindowHandoffTest {
    @Test fun drawerAndRecentsKeepStockTintThenReturningHomeCanAdaptAgain() {
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false)) // Home
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false)) // Drawer
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, true)) // Recents over drawer
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false)) // Back to drawer
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false)) // Back Home
    }

    @Test fun systemUiRestartKeepsNativeHomeTintUntilLauncherReplies() {
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(null, false))
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false))
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false))
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(null, true))
    }

    @Test fun launcherRecentsSignalWinsBeforeTheTaskCallbackArrives() {
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(true, false))
        // A stale Home signal cannot override an identified Recents task either.
        assertFalse(StatusBarAppearance.launcherAllowsAdaptiveTint(false, true))
        assertTrue(StatusBarAppearance.launcherAllowsAdaptiveTint(false, false))
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

    @Test fun launcherOverviewAndIdentifiedSystemUiRecentsRetainNativeColours() {
        assertFalse(StatusBarAppearance.windowAllowsAdaptiveTint("com.sec.android.app.launcher", true))
        assertFalse(StatusBarAppearance.windowAllowsAdaptiveTint("com.android.systemui", true))
        assertFalse(StatusBarAppearance.windowAllowsAdaptiveTint(null, true))
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
