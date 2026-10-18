package io.github.soclear.oneuix.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarSenderTest {
    @Test fun launcherAndSystemUiMustProvideTheirActualFrameworkIdentity() {
        for (sender in listOf("com.sec.android.app.launcher", "com.android.systemui")) {
            assertTrue(StatusBarSender.accepts(sender, 10123, sender, 10123))
            assertFalse(StatusBarSender.accepts(sender, 10123, sender, 10124))
            assertFalse(StatusBarSender.accepts(sender, 10123, "other.app", 10123))
            assertFalse(StatusBarSender.accepts(sender, 10123, null, -1))
            assertFalse(StatusBarSender.accepts(sender, -1, sender, -1))
            assertFalse(StatusBarSender.accepts(sender, 10123, sender, 110123))
        }
    }
}
