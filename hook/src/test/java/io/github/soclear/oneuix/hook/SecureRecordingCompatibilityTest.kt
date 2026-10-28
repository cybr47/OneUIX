package io.github.soclear.oneuix.hook

import org.junit.Assert.*
import org.junit.Test

class SecureRecordingCompatibilityTest {
    private val adapter = listOf(
        "android.hardware.display.IVirtualDisplayCallback",
        "android.media.projection.IMediaProjection",
        "int", "java.lang.String", "java.lang.String", "android.view.Surface",
        "int", "android.hardware.display.VirtualDisplayConfig"
    )

    @Test fun matchesBothDisplayControlOverloadsUsedByAndroid() {
        assertTrue(SecureRecordingCompatibility.isDisplayControlCreation(
            "createVirtualDisplay", listOf("java.lang.String", "boolean")
        ))
        assertTrue(SecureRecordingCompatibility.isDisplayControlCreation(
            "createVirtualDisplay", listOf("java.lang.String", "boolean", "java.lang.String", "float")
        ))
        assertTrue(SecureRecordingCompatibility.isDisplayControlCreation(
            "createDisplay", listOf("java.lang.String", "boolean")
        ))
    }

    @Test fun doesNotRewriteUnrelatedBooleanParameters() {
        assertFalse(SecureRecordingCompatibility.isDisplayControlCreation(
            "setDisplayState", listOf("java.lang.String", "boolean")
        ))
        assertFalse(SecureRecordingCompatibility.isDisplayControlCreation(
            "createVirtualDisplay", listOf("java.lang.String", "int", "boolean")
        ))
        assertFalse(SecureRecordingCompatibility.isDisplayControlCreation(
            "createVirtualDisplay", listOf("java.lang.String", "boolean", "int", "int")
        ))
    }

    @Test fun findsFlagsAfterSurfaceWithoutConfusingThemWithOwnerUid() {
        assertEquals(6, SecureRecordingCompatibility.virtualDisplayFlagsIndex(
            "createVirtualDisplayLocked", adapter
        ))
        val service = adapter.toMutableList().apply {
            add(5, "android.companion.virtual.IVirtualDevice")
        }
        assertEquals(7, SecureRecordingCompatibility.virtualDisplayFlagsIndex(
            "createVirtualDisplayLocked", service
        ))
    }

    @Test fun skipsUnknownServerSignaturesInsteadOfChangingADimensionOrUid() {
        assertNull(SecureRecordingCompatibility.virtualDisplayFlagsIndex(
            "createVirtualDisplayLocked", adapter.toMutableList().apply { add(6, "int") }
        ))
        assertNull(SecureRecordingCompatibility.virtualDisplayFlagsIndex(
            "createVirtualDisplayLocked", adapter.toMutableList().apply { this[1] = "java.lang.Object" }
        ))
        assertNull(SecureRecordingCompatibility.virtualDisplayFlagsIndex("resizeVirtualDisplayLocked", adapter))
    }

    @Test fun matchesTheSmartCaptureApkCallWithoutHookingTheBooleanOverload() {
        val call = listOf(
            "java.lang.String", "int", "int", "int", "int", "android.view.Surface",
            "android.hardware.display.VirtualDisplay\$Callback", "android.os.Handler"
        )
        assertTrue(SecureRecordingCompatibility.isMediaProjectionCreation("createVirtualDisplay", call))
        assertFalse(SecureRecordingCompatibility.isMediaProjectionCreation(
            "createVirtualDisplay", call.toMutableList().apply { this[4] = "boolean" }
        ))
        assertFalse(SecureRecordingCompatibility.isMediaProjectionCreation("createVirtualDisplay", call.dropLast(1)))
    }
}
