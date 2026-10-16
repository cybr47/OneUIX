package io.github.soclear.oneuix.common

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class FodConfigTest {
    @Test fun oldPreferencesKeepFodOffAndKeepExistingFeatures() {
        val preference = IgnoreUnknownKeysJson.decodeFromString<Preference>(
            """{"systemUI":{"qs":{"audioEraserQsTile":true},"statusBar":{"hideLockscreenStatusBar":true}}}""")
        assertFalse(preference.systemUI.fod.hideIcon)
        assertFalse(preference.systemUI.fod.hasCustomIcon)
        assertEquals(1f, preference.systemUI.fod.scale, 0f)
        assertTrue(preference.systemUI.qs.audioEraserQsTileEnabled)
        assertTrue(preference.systemUI.statusBar.hideLockscreenStatusBar)
    }

    @Test fun selectedIconAndSizeSurvivePreferenceRoundTrip() {
        val fod = FodConfig(true, 1, "a".repeat(64), 150)
        val preference = Preference(systemUI = Preference.SystemUI(fod = fod))
        val restored = IgnoreUnknownKeysJson.decodeFromString<Preference>(
            IgnoreUnknownKeysJson.encodeToString(Preference.serializer(), preference))
        assertEquals(fod, restored.systemUI.fod)
        assertEquals("fod-icon-1.png", restored.systemUI.fod.iconFileName)
        // Switching off retains the selection for the next enable.
        assertTrue(fod.copy(hideIcon = false).hasCustomIcon)
        assertNull(fod.copy(iconSlot = -1, iconDigest = "").iconFileName)
    }

    @Test fun restoredSizesCannotExceedTheSupportedRange() {
        assertEquals(.5f, FodConfig(sizePercent = Int.MIN_VALUE).scale, 0f)
        assertEquals(1.5f, FodConfig(sizePercent = Int.MAX_VALUE).scale, 0f)
    }

    @Test fun restoredMetadataCannotSelectArbitraryRemoteFiles() {
        assertNull(FodConfig(iconSlot = 2, iconDigest = "a".repeat(64)).iconFileName)
        assertNull(FodConfig(iconSlot = 0, iconDigest = "../preference.json").iconFileName)
        assertNull(FodConfig(iconSlot = 0, iconDigest = "A".repeat(64)).iconFileName)
    }

    @Test fun pngHeaderBoundsRejectDangerousAllocationsAndOtherFormats() {
        assertEquals(512 to 64, FodConfig.pngDimensions(header(512, 64), 512))
        for (bad in listOf(header(0, 20), header(513, 20), header(-1, 20),
            header(20, Int.MAX_VALUE), byteArrayOf(1, 2, 3), header(1, 1).copyOf(24))) {
            assertThrows(IllegalArgumentException::class.java) { FodConfig.pngDimensions(bad, 512) }
        }
        val wrongChunk = header(10, 10).apply { this[12] = 'X'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) { FodConfig.pngDimensions(wrongChunk, 512) }
    }

    @Test fun readsEnforceTheLimitWithoutDependingOnAvailable() {
        val bytes = ByteArray(8193) { 3 }
        val input = object : ByteArrayInputStream(bytes) { override fun available() = 0 }
        assertArrayEquals(bytes, FodConfig.readBounded(input, bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            FodConfig.readBounded(ByteArrayInputStream(bytes), bytes.size - 1)
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            FodConfig.digest(byteArrayOf()))
    }

    private fun header(width: Int, height: Int): ByteArray = ByteBuffer.allocate(33)
        .put(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        .putInt(13).put("IHDR".toByteArray()).putInt(width).putInt(height).array()
}
