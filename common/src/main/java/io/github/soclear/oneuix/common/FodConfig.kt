package io.github.soclear.oneuix.common

import kotlinx.serialization.Serializable
import java.io.InputStream

@Serializable
data class FodConfig(
    val hideIcon: Boolean = false,
) {
    companion object {
        // A package-targeted notification only: receivers read the trusted remote file.
        val changeAction = BuildConfig.MODULE_APPLICATION_ID + ".FOD_CHANGED"
        val changePermission = BuildConfig.MODULE_APPLICATION_ID + ".permission.FOD_REFRESH"

        fun readBounded(input: InputStream, limit: Int): ByteArray {
            require(limit > 0)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (count == 0) {
                    // Some provider streams make no progress on a bulk read.
                    val next = input.read()
                    if (next == -1) break
                    require(output.size() < limit) { "File is too large" }
                    output.write(next)
                } else {
                    require(output.size().toLong() + count <= limit) { "File is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }
}
