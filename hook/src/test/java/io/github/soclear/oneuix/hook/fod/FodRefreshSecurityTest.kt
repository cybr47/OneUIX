package io.github.soclear.oneuix.hook.fod

import io.github.soclear.oneuix.common.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class FodRefreshSecurityTest {
    @Test fun acceptsOnlyTheInstalledModuleIdentity() {
        val module = BuildConfig.MODULE_APPLICATION_ID
        assertTrue(FodRefreshSecurity.acceptsSender(10321, 10321, module))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, 10322, module))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, 10321, "another.app"))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, 10321, null))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, -1, module))
        assertFalse(FodRefreshSecurity.acceptsSender(-1, -1, module))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, 0, module))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, 1000, module))
        assertFalse(FodRefreshSecurity.acceptsSender(10321, 110321, module))
    }

    @Test fun burstBeforeAndDuringAReadIsCoalescedWithoutOverlapping() {
        val gate = FodReloadGate()
        assertFalse(gate.start())
        repeat(10000) { assertTrue(gate.request()) }
        assertTrue(gate.start())
        assertFalse(gate.start())
        repeat(10000) { assertFalse(gate.request()) }
        assertFalse(gate.start())
        assertTrue(gate.finish())
        assertTrue(gate.start())
        assertFalse(gate.finish())
        assertFalse(gate.start())
        // A later change is still accepted; no permanent throttling or polling.
        assertTrue(gate.request())
        assertTrue(gate.start())
        assertFalse(gate.finish())
    }
}
