package io.github.soclear.oneuix.hook.fod

import org.junit.Assert.*
import org.junit.Test

class FodCompatibilityTest {
    @Test fun usesNativeLayoutWithoutAnySensorInfoMethod() {
        // Stale measured bounds must not win after a native density/rotation update.
        assertEquals(FodCompatibility.IconSize(226, 230),
            FodCompatibility.iconSize(226, 230, 180, 180))
    }

    @Test fun waitsForLayoutInsteadOfInventingADefaultSize() {
        assertNull(FodCompatibility.iconSize(-2, -2, 0, 0))
        assertEquals(FodCompatibility.IconSize(192, 192),
            FodCompatibility.iconSize(-2, -2, 192, 192))
        assertNull(FodCompatibility.iconSize(0, 0, 192, 0))
        assertNull(FodCompatibility.iconSize(8192, 192, 192, 192))
    }

    @Test fun findsToastUpdatesWithoutInnerClassMetadata() {
        val toast = StrippedToast::class.java
        val typeName = toast.name + "\$ToastType"
        val type = Class.forName(typeName)
        assertNotEquals("ToastType", type.simpleName) // The previous predicate fails here.
        val updates = FodCompatibility.toastUpdates(toast)
        assertEquals(setOf(2, 5), updates.map { it.parameterCount }.toSet())
        assertTrue(updates.all { it.parameterTypes.any { parameter -> parameter == type } })
    }
}
