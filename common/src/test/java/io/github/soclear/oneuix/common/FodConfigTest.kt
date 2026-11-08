package io.github.soclear.oneuix.common

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class FodConfigTest {
    @Test fun hideToggleDefaultsOffAndCanBeEnabled() {
        assertEquals(FodConfig(), FodConfig(false))
        assertEquals(FodConfig(true), FodConfig(hideIcon = true))
    }

    @Test fun preferenceReadsIgnoreLegacyCustomIconFields() {
        val oldFod = """{"hideIcon":true,"iconSlot":1,"iconDigest":"legacy","iconFormat":"PNG","sizePercent":125}"""
        val decoded = FodPreferences.read("""{"systemUI":{"fod":$oldFod}}""".byteInputStream())
        assertEquals(FodConfig(true), decoded)
    }

    @Test fun boundedPreferenceReadRejectsOversizedInput() {
        val bytes = ByteArray(32) { 3 }
        assertArrayEquals(bytes, FodConfig.readBounded(ByteArrayInputStream(bytes), bytes.size))
        assertThrows(IllegalArgumentException::class.java) {
            FodConfig.readBounded(ByteArrayInputStream(bytes), bytes.size - 1)
        }
    }
}
