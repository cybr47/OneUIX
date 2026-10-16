package io.github.soclear.oneuix.hook.fod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.FodPng
import io.github.soclear.oneuix.hook.util.PreferenceProvider
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class FodRuntime(
    private val module: XposedModule,
    context: Context,
    initial: FodConfig,
    private val loadIcon: Boolean = true,
) {
    data class State(val config: FodConfig, val bitmap: Bitmap?)

    @Volatile var state = State(initial, null)
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>())
    private val listeners = mutableListOf<() -> Unit>()
    private val reported = mutableSetOf<String>()
    private val reload = Runnable {
        worker.execute {
            val config = with(module) { PreferenceProvider.loadPreference() }?.systemUI?.fod
                ?: return@execute
            val previous = state
            val bitmap = if (!loadIcon || !config.hideIcon || !config.hasCustomIcon) null
            else if (config.iconDigest == previous.config.iconDigest && previous.bitmap != null) previous.bitmap
            else runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(requireNotNull(config.iconFileName))).use {
                    FodPng.decodeStored(FodConfig.readBounded(it, FodConfig.MAX_STORED_BYTES), config.iconDigest)
                }
            }.onFailure { report("Cannot load selected PNG; keeping FOD hidden", it) }.getOrNull()
            if (bitmap != null) report("Loaded custom FOD PNG")
            main.post {
                state = State(config, bitmap)
                listeners.forEach { listener -> safely("Refresh FOD visuals", listener) }
            }
        }
    }

    init {
        // No intent extras are trusted. This only invalidates data owned by the module.
        // Both target processes run with a different UID, so an exported receiver is needed.
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == FodConfig.changeAction) requestReload()
            }
        }, IntentFilter(FodConfig.changeAction), Context.RECEIVER_EXPORTED)
        requestReload()
    }

    fun onChanged(listener: () -> Unit) { listeners.add(listener) }

    private fun requestReload() {
        main.removeCallbacks(reload)
        main.postDelayed(reload, 100)
    }

    fun safely(operation: String, action: () -> Unit) {
        try { action() } catch (t: Throwable) { report(operation, t) }
    }

    fun report(message: String, error: Throwable? = null) {
        if (synchronized(reported) { reported.add(message) }) {
            if (error == null) module.log(Log.INFO, null, "[OneUIX FOD] $message")
            else module.log(Log.ERROR, null, "[OneUIX FOD] $message", error)
        }
    }
}
