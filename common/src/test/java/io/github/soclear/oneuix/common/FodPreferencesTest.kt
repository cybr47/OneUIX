package io.github.soclear.oneuix.common

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class FodPreferencesTest {
    private fun read(text: String) = FodPreferences.read(text.byteInputStream())

    @Test fun readsTheTestedFormatWithoutLosingSelectionOrSize() {
        val config = FodConfig(true, 1, "a".repeat(64), 150)
        val preference = Preference(systemUI = Preference.SystemUI(fod = config))
        assertEquals(config, read(IgnoreUnknownKeysJson.encodeToString(Preference.serializer(), preference)))
        assertEquals(FodConfig(), read("""{"android":{"disablePinVerifyPer72h":true}}"""))
        assertEquals(FodConfig(), read("{}"))
        assertEquals(FodConfig(), read(""))
    }

    @Test fun rejectsOversizedAndDeeplyNestedSettingsBeforeParsing() {
        val huge = " ".repeat(FodPreferences.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { read(huge) }
        val nested = """{"unknown":${"[".repeat(33)}0${"]".repeat(33)}}"""
        assertThrows(IllegalArgumentException::class.java) { read(nested) }
    }

    @Test fun rejectsInvalidUtf8AndTruncatedSettings() {
        assertThrows(Exception::class.java) {
            FodPreferences.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        for (text in listOf("""{"systemUI":{ """, """{"systemUI":{"fod":{"hideIcon":"yes"}}}""",
            """{"unknown":"unterminated}""")) {
            assertThrows(Exception::class.java) { read(text) }
        }
    }

    @Test fun bracesAndEscapedQuotesInStringsAreNotNesting() {
        assertEquals(FodConfig(), read("""{"unknown":"[[[\\\"}]]"}"""))
    }

    @Test fun zeroLengthBulkReadsCannotSpinForeverOrBypassTheLimit() {
        fun stream(): InputStream = object : InputStream() {
            private var remaining = 3
            override fun read(b: ByteArray, off: Int, len: Int) = 0
            override fun read(): Int = if (remaining-- > 0) 7 else -1
        }
        assertArrayEquals(byteArrayOf(7, 7, 7), FodConfig.readBounded(stream(), 3))
        assertThrows(IllegalArgumentException::class.java) { FodConfig.readBounded(stream(), 2) }
    }
}
