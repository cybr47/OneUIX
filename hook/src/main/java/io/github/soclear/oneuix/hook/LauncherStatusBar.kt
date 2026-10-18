package io.github.soclear.oneuix.hook

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Window
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.xlog
import java.lang.ref.WeakReference

/** Observe the same screen events that Samsung uses to request its native icon colour. */
internal object LauncherStatusBar {
    context(module: XposedModule)
    fun install() = afterAttachTry {
        Controller(this, module).install()
    }

    private class Controller(private val context: Context, private val module: XposedModule) {
        private val main = Handler(Looper.getMainLooper())
        private var manager = WeakReference<Any>(null)
        private var lastPublished: Boolean? = null
        private val reported = mutableSetOf<String>()
        private val loader = context.classLoader
        private val pot = loader.loadClass("com.honeyspace.common.entity.HoneyPot")
        private val getContext = pot.getDeclaredMethod("getContext")
        private val getManager = pot.getDeclaredMethod("getHoneyScreenManager")
        private val getWindow = loader.loadClass("com.honeyspace.sdk.HoneyWindowController")
            .getDeclaredMethod("getWindowInfo", Context::class.java)
        private val screenManager = loader.loadClass("com.honeyspace.sdk.HoneyScreenManager")
        private val getScreen = screenManager.getDeclaredMethod("getCurrentHoneyScreen")
        private val screenName = loader.loadClass("com.honeyspace.sdk.HoneyScreen\$Name")
        private val apps = screenName.getField("APPS").get(null)
        private val reconcile = Runnable { safely("screen state") { publishCurrent() } }

        private fun safely(part: String, action: () -> Unit) {
            try { action() } catch (t: Throwable) {
                if (reported.add(part)) with(module) { xlog("Launcher status bar: $part unavailable", t) }
            }
        }

        @Suppress("DEPRECATION") // The WindowManager also identifies a not-yet-attached window.
        private fun capture(instance: Any?): Boolean {
            if (instance == null) return false
            val host = getContext.invoke(instance) as? Context ?: return false
            // Samsung can create other HoneySpaces for DeX / secondary displays.
            // Resolve its actual window: a HoneyPot may use a non-visual Context wrapper.
            val controller = instance.javaClass.getMethod("getHoneyWindowController").invoke(instance)
            val window = getWindow.invoke(controller, host) as? Window ?: return false
            val display = window.decorView.display ?: window.windowManager?.defaultDisplay
            if (display?.displayId != 0) return false
            manager = WeakReference(getManager.invoke(instance))
            return true
        }

        private fun publish(drawerOpen: Boolean, force: Boolean = false) {
            if (!force && lastPublished == drawerOpen) return
            LauncherStatusBarBridge.publish(context, drawerOpen)
            lastPublished = drawerOpen
        }

        private fun publishCurrent(force: Boolean = false) {
            val current = manager.get() ?: return
            publish(getScreen.invoke(current) == apps, force)
        }

        private fun scheduleReconcile() {
            main.removeCallbacks(reconcile)
            main.post(reconcile)
        }

        fun install() {
            val home = loader.loadClass("com.honeyspace.ui.honeypots.homescreen.presentation.HomescreenPot")
            val drawer = loader.loadClass("com.honeyspace.ui.honeypots.appscreen.presentation.AppscreenPot")
            val homeColour = home.getDeclaredMethod("updateSystemUIColor", Boolean::class.javaPrimitiveType)
            val drawerColour = drawer.getDeclaredMethod("updateSystemUIColor")
            listOf(home, drawer).forEach { it.getMethod("getHoneyWindowController") }
            val settled = listOf(home, drawer).flatMap { type ->
                listOf(type.getDeclaredMethod("onShown"), type.getDeclaredMethod("onCancelScreenAnimation"))
            }
            val resume = loader.loadClass("com.sec.android.app.launcher.Launcher").getDeclaredMethod("onResume")

            // Validate the complete APK contract before registering or installing hooks.
            LauncherStatusBarBridge.listenForQuery(context, main) {
                safely("state reply") { publishCurrent(force = true) }
            }
            listOf(homeColour to false, drawerColour to true).forEach { (method, isDrawer) ->
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    safely("native colour event") {
                        if (capture(chain.thisObject)) publish(isDrawer)
                    }
                    result
                }
            }
            // Re-read Samsung's actual screen after a completed or cancelled swipe.
            // Never infer state from search-bar visibility (the user may hide it).
            settled.forEach { method ->
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    safely("settled screen") { if (capture(chain.thisObject)) scheduleReconcile() }
                    result
                }
            }
            module.hook(resume).intercept { chain ->
                val result = chain.proceed()
                if ((chain.thisObject as? Activity)?.display?.displayId == 0) {
                    main.post { safely("resume state") { publishCurrent(force = true) } }
                }
                result
            }
        }
    }
}
