package io.github.soclear.oneuix.common

import android.graphics.Picture
import com.caverock.androidsvg.SVG
import java.io.ByteArrayInputStream

/** Bounded SVG import and a cached, resolution-independent display list for lockscreen drawing. */
object FodSvg {
    private const val VIEWPORT_SIDE = 512

    private val forbiddenXml = Regex("<!\\s*(DOCTYPE|ENTITY)\\b", RegexOption.IGNORE_CASE)
    private val activeContent = Regex("<\\s*(script|foreignObject)\\b", RegexOption.IGNORE_CASE)
    private val externalReference = Regex(
        """(?:href|xlink:href)\s*=\s*['"]\s*(?:https?:|file:|content:|//)""",
        RegexOption.IGNORE_CASE,
    )

    /** Validate while importing; keep the compact source vector for later lossless drawing. */
    fun normalize(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..FodConfig.MAX_STORED_BYTES) { "SVG is too large" }
        render(bytes)
        return bytes
    }

    /** Parse and record once on the FOD worker thread; drawing the Picture scales as a vector. */
    fun decodeStored(bytes: ByteArray, expectedDigest: String): Picture {
        require(bytes.size in 1..FodConfig.MAX_STORED_BYTES) { "SVG is too large" }
        require(FodConfig.digest(bytes) == expectedDigest) { "SVG changed or is incomplete" }
        return render(bytes)
    }

    private fun render(bytes: ByteArray): Picture {
        val source = bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
        require(!forbiddenXml.containsMatchIn(source)) { "SVG DTDs and entities are not supported" }
        require(!activeContent.containsMatchIn(source)) { "Active SVG content is not supported" }
        require(!externalReference.containsMatchIn(source)) { "External SVG references are not supported" }

        val svg = SVG.getFromInputStream(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8)))
        return svg.renderToPicture(VIEWPORT_SIDE, VIEWPORT_SIDE)
    }
}
