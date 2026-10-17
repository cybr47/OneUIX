package io.github.soclear.oneuix.hook.fod

import io.github.soclear.oneuix.common.BuildConfig

internal object FodRefreshSecurity {
    // Values must come from Android's BroadcastReceiver API, never intent extras.
    fun acceptsSender(expectedUid: Int, senderUid: Int, senderPackage: String?): Boolean =
        expectedUid >= 0 && senderUid == expectedUid &&
            senderPackage == BuildConfig.MODULE_APPLICATION_ID
}

/** Main-thread state: at most one active read and one coalesced follow-up. */
internal class FodReloadGate {
    private var pending = false
    private var running = false

    fun request(): Boolean {
        pending = true
        return !running
    }

    fun start(): Boolean {
        if (running || !pending) return false
        pending = false
        running = true
        return true
    }

    fun finish(): Boolean {
        running = false
        return pending
    }
}
