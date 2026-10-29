package io.github.soclear.oneuix.hook.fod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PermissionInfo
import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.BuildConfig
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.FodIconFormat
import io.github.soclear.oneuix.common.FodPng
import io.github.soclear.oneuix.common.FodSvg
import io.github.soclear.oneuix.common.FodPreferences
import io.github.soclear.oneuix.common.Preference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class FodRuntime(
    private val module: XposedModule,
    context: Context,
    initial: FodConfig,
    private val loadIcon: Boolean = true,
) {
    data class State(val config: FodConfig, val bitmap: Bitmap?, val picture: Picture?)

    @Volatile var state = State(initial, null, null)
        private set
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1))
    private val reloadGate = FodReloadGate()
    private var scheduled = false
    private val listeners = mutableListOf<() -> Unit>()
    private val reported = mutableSetOf<String>()
    private val reload = Runnable {
        scheduled = false
        if (reloadGate.start()) startReload()
    }

    private fun startReload() {
        worker.execute {
            val next = runCatching { loadState() }.onFailure {
                // Parser exceptions may contain preference contents. Do not log them.
                report("Cannot read FOD settings; restoring native visuals")
            }.getOrElse { State(FodConfig(), null, null) }
            main.post {
                state = next
                listeners.forEach { listener -> safely("Refresh FOD visuals", listener) }
                if (reloadGate.finish()) scheduleReload()
            }
        }
    }

    private fun loadState(): State {
        val config = ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(Preference.FILE_NAME)).use {
            FodPreferences.read(it)
        }
        val previous = state
        val canReuse = config.iconDigest == previous.config.iconDigest &&
            config.iconFormat == previous.config.iconFormat
        var bitmap: Bitmap? = null
        var picture: Picture? = null
        if (loadIcon && config.hideIcon && config.hasCustomIcon) {
            if (canReuse && previous.bitmap != null) {
                bitmap = previous.bitmap
            } else if (canReuse && previous.picture != null) {
                picture = previous.picture
            } else {
                runCatching {
                    val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                        module.openRemoteFile(requireNotNull(config.iconFileName))
                    ).use { FodConfig.readBounded(it, FodConfig.MAX_STORED_BYTES) }
                    when (config.iconFormat) {
                        FodIconFormat.PNG -> bitmap = FodPng.decodeStored(bytes, config.iconDigest)
                        FodIconFormat.SVG -> picture = FodSvg.decodeStored(bytes, config.iconDigest)
                    }
                }.onFailure { report("Cannot load selected FOD icon; native glyph remains hidden", it) }
            }
        }
        return State(config, bitmap, picture)
    }

    init {
        safely("Cannot register authenticated FOD refresh; restart the host to reload settings") {
            registerRefreshReceiver(context)
        }
        requestReload(immediate = true)
    }

    private fun registerRefreshReceiver(context: Context) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        val moduleInfo = module.moduleApplicationInfo
        check(moduleInfo.packageName == BuildConfig.MODULE_APPLICATION_ID)
        val moduleUid = moduleInfo.uid

        // Android checks the sender's signature permission before delivery. Also
        // require its framework-supplied identity, not a caller-controlled extra.
        // Registration failure never falls back to an unauthenticated receiver.
        context.registerReceiver(object : BroadcastReceiver() {
            private var permissionVerified = false

            override fun onReceive(context: Context, intent: Intent) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    intent.action == FodConfig.changeAction &&
                    FodRefreshSecurity.acceptsSender(moduleUid, sentFromUid, sentFromPackage)) {
                    safely("Cannot authenticate FOD refresh permission") {
                        if (!permissionVerified) {
                            // Wait for the authenticated sender to share its identity;
                            // do not require module package visibility at host startup.
                            val permission = context.packageManager.getPermissionInfo(FodConfig.changePermission, 0)
                            check(permission.packageName == BuildConfig.MODULE_APPLICATION_ID &&
                                permission.protection == PermissionInfo.PROTECTION_SIGNATURE)
                            permissionVerified = true
                        }
                        requestReload()
                    }
                }
            }
        }, IntentFilter(FodConfig.changeAction), FodConfig.changePermission, main, Context.RECEIVER_EXPORTED)
    }

    fun onChanged(listener: () -> Unit) { listeners.add(listener) }

    private fun requestReload(immediate: Boolean = false) {
        if (reloadGate.request()) scheduleReload(if (immediate) 0L else 250L)
    }

    private fun scheduleReload(delayMillis: Long = 250L) {
        if (scheduled) return
        scheduled = true
        // Initial reads start promptly; subsequent updates are coalesced without blocking UI.
        main.postDelayed(reload, delayMillis)
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
