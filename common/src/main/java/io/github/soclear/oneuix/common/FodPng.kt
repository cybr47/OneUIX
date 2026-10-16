package io.github.soclear.oneuix.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object FodPng {
    fun normalize(bytes: ByteArray): ByteArray {
        require(bytes.size <= FodConfig.MAX_IMPORT_BYTES)
        val (width, height) = FodConfig.pngDimensions(bytes, 8192)
        var sample = 1
        while (max(width, height) / sample > FodConfig.MAX_STORED_SIDE * 2) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            })) { "Cannot decode PNG" }
        try {
            val ratio = minOf(1f, FodConfig.MAX_STORED_SIDE.toFloat() / max(decoded.width, decoded.height))
            val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(decoded,
                max(1, (decoded.width * ratio).roundToInt()),
                max(1, (decoded.height * ratio).roundToInt()), true) else decoded
            try {
                val output = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                return output.toByteArray().also { require(it.size <= FodConfig.MAX_STORED_BYTES) }
            } finally {
                if (bitmap !== decoded) bitmap.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun decodeStored(bytes: ByteArray, expectedDigest: String): Bitmap {
        require(bytes.size <= FodConfig.MAX_STORED_BYTES)
        FodConfig.pngDimensions(bytes, FodConfig.MAX_STORED_SIDE)
        require(FodConfig.digest(bytes) == expectedDigest) { "PNG changed or is incomplete" }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }))
    }
}
