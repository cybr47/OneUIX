package io.github.soclear.oneuix.ui

import android.content.Context
import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.soclear.oneuix.XposedServiceManager
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.FodPng

object FodIconStore {
    // Alternate two files so a reader never sees a partially overwritten selected PNG.
    fun importIcon(context: Context, uri: Uri, current: FodConfig): FodConfig {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Choose a PNG from the document picker" }
        val service = checkNotNull(XposedServiceManager.xposedService) { "Module is not active" }
        val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use {
            FodPng.normalize(FodConfig.readBounded(it, FodConfig.MAX_IMPORT_BYTES))
        }
        val slot = if (current.iconSlot == 0) 1 else 0
        ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(FodConfig.fileName(slot))).use {
            it.channel.truncate(0)
            it.write(bytes)
            it.channel.force(true)
        }
        return current.copy(iconSlot = slot, iconDigest = FodConfig.digest(bytes))
    }

    fun preview(config: FodConfig): Bitmap? {
        val name = config.iconFileName ?: return null
        val service = XposedServiceManager.xposedService ?: return null
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(service.openRemoteFile(name)).use {
                FodPng.decodeStored(FodConfig.readBounded(it, FodConfig.MAX_STORED_BYTES), config.iconDigest)
            }
        }.getOrNull()
    }
}
