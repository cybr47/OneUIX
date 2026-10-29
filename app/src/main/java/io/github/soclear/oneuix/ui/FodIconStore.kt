package io.github.soclear.oneuix.ui

import android.content.Context
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.soclear.oneuix.XposedServiceManager
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.FodIconFormat
import io.github.soclear.oneuix.common.FodSvg
import io.github.soclear.oneuix.common.FodPng

object FodIconStore {
    // Alternate two files so a reader never sees a partially overwritten selected PNG.
    fun importIcon(context: Context, uri: Uri, current: FodConfig): FodConfig {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a PNG from the document picker" }
        val service = checkNotNull(XposedServiceManager.xposedService) { "Module is not active" }
        val resolver = context.contentResolver
        val format = when {
            resolver.getType(uri)?.contains("svg", ignoreCase = true) == true ||
                displayName(context, uri).endsWith(".svg", ignoreCase = true) -> FodIconFormat.SVG
            resolver.getType(uri) == "image/png" ||
                displayName(context, uri).endsWith(".png", ignoreCase = true) -> FodIconFormat.PNG
            else -> error("Choose a PNG or SVG image")
        }
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { input ->
            val source = FodConfig.readBounded(input, FodConfig.MAX_IMPORT_BYTES)
            when (format) {
                FodIconFormat.PNG -> FodPng.normalize(source)
                FodIconFormat.SVG -> FodSvg.normalize(source)
            }
        }
        val slot = if (current.iconSlot == 0) 1 else 0
        ParcelFileDescriptor.AutoCloseOutputStream(
            service.openRemoteFile(FodConfig.fileName(slot, format))
        ).use {
            it.channel.truncate(0)
            it.write(bytes)
            it.channel.force(true)
        }
        return current.copy(iconSlot = slot, iconDigest = FodConfig.digest(bytes), iconFormat = format)
    }

    fun preview(config: FodConfig): Bitmap? {
        val name = config.iconFileName ?: return null
        val service = XposedServiceManager.xposedService ?: return null
        return runCatching {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name)).use {
                FodConfig.readBounded(it, FodConfig.MAX_STORED_BYTES)
            }
            when (config.iconFormat) {
                FodIconFormat.PNG -> FodPng.decodeStored(bytes, config.iconDigest)
                FodIconFormat.SVG -> {
                    val picture = FodSvg.decodeStored(bytes, config.iconDigest)
                    Bitmap.createBitmap(FodConfig.MAX_STORED_SIDE, FodConfig.MAX_STORED_SIDE,
                        Bitmap.Config.ARGB_8888).also { bitmap ->
                        Canvas(bitmap).drawPicture(picture, Rect(0, 0, bitmap.width, bitmap.height))
                    }
                }
            }
        }.getOrNull()
    }

    private fun displayName(context: Context, uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }
            .orEmpty()
    }
}
