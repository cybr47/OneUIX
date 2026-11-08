package io.github.soclear.oneuix.common

import kotlinx.serialization.Serializable
import java.io.InputStream

/** Read only the FOD portion, with limits before the JSON parser enters host processes. */
object FodPreferences {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_DEPTH = 32

    @Serializable
    private data class Snapshot(val systemUI: SystemUi = SystemUi())

    @Serializable
    private data class SystemUi(val fod: FodConfig = FodConfig())

    fun read(input: InputStream): FodConfig {
        val bytes = FodConfig.readBounded(input, MAX_BYTES)
        if (bytes.isEmpty()) return FodConfig()
        val text = bytes.decodeToString(throwOnInvalidSequence = true)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= MAX_DEPTH) { "Preferences are nested too deeply" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid preferences" } }
            }
        }
        require(!quoted && depth == 0) { "Incomplete preferences" }
        return IgnoreUnknownKeysJson.decodeFromString<Snapshot>(text).systemUI.fod
    }
}
