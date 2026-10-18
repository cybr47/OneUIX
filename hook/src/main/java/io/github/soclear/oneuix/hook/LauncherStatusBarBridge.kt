package io.github.soclear.oneuix.hook

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import io.github.soclear.oneuix.common.BuildConfig
import io.github.soclear.oneuix.common.Package

/** One Boolean, sent only between the scoped launcher and SystemUI processes. */
internal object LauncherStatusBarBridge {
    private val stateAction = BuildConfig.MODULE_APPLICATION_ID + ".statusbar.NATIVE_APPEARANCE_STATE"
    private val queryAction = BuildConfig.MODULE_APPLICATION_ID + ".statusbar.QUERY_NATIVE_APPEARANCE"
    private const val nativeAppearanceExtra = "nativeLauncherAppearance"
    // Both supplied Samsung APKs already hold this platform permission.
    private const val permission = "android.permission.STATUS_BAR_SERVICE"

    fun listenForState(context: Context, main: Handler, onState: (Boolean) -> Unit) =
        register(context, main, stateAction, Package.LAUNCHER) { intent ->
            if (intent.hasExtra(nativeAppearanceExtra)) onState(intent.getBooleanExtra(nativeAppearanceExtra, true))
        }

    fun listenForQuery(context: Context, main: Handler, onQuery: () -> Unit) =
        register(context, main, queryAction, Package.SYSTEMUI) { onQuery() }

    fun publish(context: Context, nativeLauncherAppearance: Boolean) = send(context,
        Intent(stateAction).setPackage(Package.SYSTEMUI).putExtra(nativeAppearanceExtra, nativeLauncherAppearance))

    fun query(context: Context) = send(context, Intent(queryAction).setPackage(Package.LAUNCHER))

    private fun send(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY or Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendBroadcast(intent, permission,
            BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
    }

    private fun register(
        context: Context, main: Handler, action: String, sender: String,
        receive: (Intent) -> Unit,
    ) {
        val uid = context.packageManager.getApplicationInfo(sender, 0).uid
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action && StatusBarSender.accepts(
                        sender, uid, sentFromPackage, sentFromUid)) receive(intent)
            }
        }, IntentFilter(action), permission, main, Context.RECEIVER_EXPORTED)
    }
}
