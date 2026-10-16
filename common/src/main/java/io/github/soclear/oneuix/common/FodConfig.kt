package io.github.soclear.oneuix.common

import kotlinx.serialization.Serializable
import java.io.InputStream
import java.security.MessageDigest

@Serializable
data class FodConfig(
    val hideIcon: Boolean = false,
    val iconSlot: Int = -1,
    val iconDigest: String = "",
    val sizePercent: Int = 100,
) {
    val scale: Float get() = sizePercent.coerceIn(50, 150) / 100f
    val hasCustomIcon: Boolean
        get() = iconSlot in 0..1 && iconDigest.matches(Regex("[0-9a-f]{64}"))
    val iconFileName: String?
        get() = if (hasCustomIcon) fileName(iconSlot) else null

    companion object {
        // A package-targeted notification only: receivers read the trusted remote file.
        val changeAction = BuildConfig.MODULE_APPLICATION_ID + ".FOD_CHANGED"
        const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val MAX_STORED_BYTES = 1100000
        const val MAX_STORED_SIDE = 512

        fun fileName(slot: Int): String {
            require(slot in 0..1)
            return "fod-icon-$slot.png"
        }

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun readBounded(input: InputStream, limit: Int): ByteArray {
            require(limit > 0)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size().toLong() + count <= limit) { "PNG file is too large" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        /** Bound allocation before passing untrusted image data to BitmapFactory. */
        fun pngDimensions(bytes: ByteArray, maxSide: Int): Pair<Int, Int> {
            val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(signature)) {
                "Choose a PNG image"
            }
            fun uint(offset: Int): Long = (0..3).fold(0L) { value, i ->
                (value shl 8) or (bytes[offset + i].toLong() and 0xff)
            }
            require(uint(8) == 13L && bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray())) {
                "Invalid PNG header"
            }
            val width = uint(16)
            val height = uint(20)
            require(width in 1..maxSide.toLong() && height in 1..maxSide.toLong()) {
                "PNG dimensions are too large"
            }
            return width.toInt() to height.toInt()
        }
    }
}
