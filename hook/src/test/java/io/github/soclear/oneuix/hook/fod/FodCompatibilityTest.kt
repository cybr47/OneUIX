package io.github.soclear.oneuix.hook.fod

import org.junit.Assert.*
import org.junit.Test

class FodCompatibilityTest {
    @Test fun findsToastUpdatesWithoutInnerClassMetadata() {
        val toast = StrippedToast::class.java
        val typeName = toast.name + "\$ToastType"
        val type = Class.forName(typeName)
        assertNotEquals("ToastType", type.simpleName)
        val updates = FodCompatibility.toastUpdates(toast)
        assertEquals(setOf(2, 5), updates.map { it.parameterCount }.toSet())
        assertTrue(updates.all { it.parameterTypes.any { parameter -> parameter == type } })
    }
}
