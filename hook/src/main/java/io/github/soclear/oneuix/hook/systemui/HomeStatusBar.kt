package io.github.soclear.oneuix.hook.systemui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Preference
import io.github.soclear.oneuix.common.StatusBarAppearance
import io.github.soclear.oneuix.hook.LauncherStatusBarBridge
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog
import java.lang.reflect.Constructor
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Home-screen dark status bar and Home clock fade.
 *
 * Dark status bar: while the launcher reports Home (not the drawer or Recents) and the shade is
 * closed, Samsung's own appearance pipeline is told the whole bar is "light", which renders its
 * native dark icons. Nothing is sampled or polled: it only reacts to events Samsung already sends.
 */
@SuppressLint("PrivateApi")
internal object HomeStatusBar {
    context(module: XposedModule)
    fun install(options: Preference.SystemUI.StatusBar) = afterAttachTry(
        options.adaptiveHomeStatusBar || options.hideClockOnHome
    ) {
        Controller(this, module, options).install()
    }

    private const val REFRESH_WINDOW_MS = 50L

    private val STATE_EVENTS = setOf(
        "onStateChanged", "onStatePostChange", "onExpandedChanged", "onDozingChanged", "onDreamingChanged"
    )

    private class Controller(
        private val context: Context,
        private val module: XposedModule,
        private val options: Preference.SystemUI.StatusBar,
    ) {
        private val loader = context.classLoader
        private val main = Handler(Looper.getMainLooper())
        private val darkBar = options.adaptiveHomeStatusBar
        private val clocks = WeakHashMap<View, HomeClockTransition>()
        private var stateController: Any? = null
        private var appearanceRepository: Any? = null
        private var sampledAppearanceFlow: Any? = null
        private var appearanceRegionConstructor: Constructor<*>? = null
        private var nativeSampledRegions: Any? = null
        private var publishedRegions: List<Any>? = null
        private var windowPackage: String? = null
        private var clockHidden = false
        private var homeTask = false
        private var recentsTask = false
        private var nativeLauncherAppearance: Boolean? = null
        private val reported = mutableSetOf<String>()
        private var refreshPending = false
        private var lastRefresh = 0L
        private val refresh = Runnable {
            refreshPending = false
            lastRefresh = SystemClock.uptimeMillis()
            safely("home state") { refreshHome() }
        }

        private fun safely(part: String, action: () -> Unit) {
            try { action() } catch (t: Throwable) {
                if (reported.add(part)) with(module) { xlog("Home status bar: $part unavailable", t) }
            }
        }

        /** Clock only: task callbacks can arrive in bursts, so they share one refresh. */
        private fun scheduleRefresh() {
            if (refreshPending) return
            refreshPending = true
            val wait = REFRESH_WINDOW_MS - (SystemClock.uptimeMillis() - lastRefresh)
            if (wait > 0) main.postDelayed(refresh, wait) else main.post(refresh)
        }

        private fun onStateEvent() {
            if (darkBar) safely("dark status bar") { updateDarkBar() }
            if (options.hideClockOnHome) scheduleRefresh()
        }

        fun install() {
            val stateClass = loader.loadClass("com.android.systemui.statusbar.StatusBarStateControllerImpl")
            val stateListener = loader.loadClass("com.android.systemui.plugins.statusbar.StatusBarStateController\$StateListener")
            val addListener = stateClass.getDeclaredMethod("addCallback", stateListener)
            stateClass.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    val instance = chain.thisObject
                    main.post {
                        safely("status listener") {
                            if (stateController !== instance && instance != null) {
                                stateController = instance
                                val listener = proxy(stateListener) { name, _ ->
                                    // Doze-amount, pulsing and fullscreen callbacks fire constantly
                                    // during transitions and change nothing used here.
                                    if (name in STATE_EVENTS) onStateEvent()
                                    null
                                }
                                addListener.invoke(instance, listener)
                                onStateEvent()
                            }
                        }
                    }
                    result
                }
            }
            if (darkBar) {
                safely("dark status bar hooks") { installAppearanceHooks() }
            }
            // The launcher says whether it shows Home, the drawer or Recents.
            safely("launcher state") {
                LauncherStatusBarBridge.listenForState(context, main) { nativeAppearance ->
                    nativeLauncherAppearance = nativeAppearance
                    if (darkBar) safely("dark status bar") { updateDarkBar() }
                    if (options.hideClockOnHome) scheduleRefresh()
                }
                // Also works when only SystemUI restarts in the drawer or Recents.
                LauncherStatusBarBridge.query(context)
            }
            if (options.hideClockOnHome) {
                val taskClass = loader.loadClass("com.android.systemui.shared.system.TaskStackChangeListeners\$Impl")
                val clockClass = loader.loadClass("com.android.systemui.statusbar.policy.QSClockIndicatorView")
                taskClass.declaredMethods.filter { it.name in setOf(
                    "onTaskStackChanged", "onTaskMovedToFront", "onTaskFocusChanged",
                    "onActivityRestartAttempt", "onActivityRotation"
                ) }.forEach { method ->
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        scheduleRefresh()
                        result
                    }
                }
                module.hook(clockClass.getDeclaredMethod("onAttachedToWindow")).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view -> safely("clock") { trackClock(view) } }
                    result
                }
                context.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) = scheduleRefresh()
                }, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }, Context.RECEIVER_NOT_EXPORTED)
                scheduleRefresh()
            }
        }

        /** Home clock fade: needs the current task to tell Home and Recents from other surfaces. */
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission") // Runs inside the already-privileged SystemUI process.
        private fun refreshHome() {
            val controller = stateController
            val state = runCatching { controller?.reflect?.call("getState") as? Int }.getOrNull()
            val expanded = runCatching { controller?.reflect?.call("isExpanded") as? Boolean }.getOrNull() ?: true
            val dozing = runCatching { controller?.reflect?.call("isDozing") as? Boolean }.getOrNull() ?: true
            val dreaming = runCatching { controller?.reflect?.call("isDreaming") as? Boolean }.getOrNull() ?: true
            val interactive = context.getSystemService(PowerManager::class.java).isInteractive
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            // Nothing below is active with the screen off, locked, dozing or the shade open, so the
            // task lookup (a binder call) is skipped. Every such state change refreshes again.
            val surfaceInactive = !interactive || locked || state != 0 || expanded || dozing || dreaming
            if (surfaceInactive) {
                clockHidden = false
                clocks.values.toList().forEach { it.setHidden(false) }
                return
            }
            // A launcher package alone is not enough: Recents and other activity types must not
            // be treated as Home. SystemUI already has permission to read the current task.
            val (isHome, isRecents) = runCatching {
                val task = context.getSystemService(ActivityManager::class.java)
                    .getRunningTasks(1).firstOrNull()
                val configuration = task?.reflect?.get("configuration")?.reflect?.get("windowConfiguration")
                val activityType = configuration?.reflect?.call("getActivityType") as? Int
                val component = task?.topActivity
                val launcher = component?.packageName == "com.sec.android.app.launcher"
                (activityType == 2 && launcher) to StatusBarAppearance.isRecentsTask(
                    activityType, component?.packageName, component?.className
                )
            }.getOrDefault(false to false)
            homeTask = isHome
            recentsTask = isRecents
            // The clock follows Home and Recents, but stays visible in the app drawer.
            // Recents can be its own activity type, so it is not required to be a Home task.
            clockHidden = StatusBarAppearance.isHomeAppearanceActive(
                StatusBarAppearance.isClockSurface(isHome, isRecents, nativeLauncherAppearance),
                interactive, locked, state, expanded, dozing || dreaming
            )
            clocks.values.toList().forEach { it.setHidden(clockHidden) }
        }

        private fun trackClock(view: View) {
            if (view.display?.displayId != 0 || clocks.containsKey(view)) return
            clocks[view] = HomeClockTransition(view)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    clocks.remove(v)?.release()
                    v.removeOnAttachStateChangeListener(this)
                }
            })
            clocks[view]?.setHidden(clockHidden)
            scheduleRefresh()
        }

        private fun darkBarAllowed(): Boolean {
            if (!StatusBarAppearance.windowAllowsAdaptiveTint(windowPackage, false)) return false
            if (!StatusBarAppearance.launcherAllowsAdaptiveTint(nativeLauncherAppearance, false)) return false
            val controller = stateController ?: return false
            val state = controller.reflect.call("getState") as? Int
            val expanded = controller.reflect.call("isExpanded") as? Boolean ?: true
            val dozing = controller.reflect.call("isDozing") as? Boolean ?: true
            val dreaming = controller.reflect.call("isDreaming") as? Boolean ?: true
            return state == 0 && !expanded && !dozing && !dreaming
        }

        private fun installAppearanceHooks() {
            // Feed the native appearance pipeline, not its final DarkIconDispatcher output.
            // LightBarController then owns both the rendered colour and animation state.
            val repository = loader.loadClass(
                "com.android.systemui.statusbar.data.repository.StatusBarModePerDisplayRepositoryImpl"
            )
            val callback = loader.loadClass(repository.name + "\$commandQueueCallback\$1")
            val flowField = repository.getDeclaredField("_sampledAppearanceRegions").apply { isAccessible = true }
            val repositoryField = callback.getDeclaredField("this\$0").apply { isAccessible = true }
            val attributesChanged = callback.declaredMethods.single {
                it.name == "onSystemBarAttributesChanged" && it.parameterCount == 8 &&
                    it.parameterTypes[6] == String::class.java
            }
            val regionType = loader.loadClass("com.android.internal.view.AppearanceRegion")
            appearanceRegionConstructor = regionType.getConstructor(Int::class.javaPrimitiveType, Rect::class.java)
            // Validate the full contract before installing any hook.
            flowField.type.getDeclaredMethod("getValue")
            flowField.type.getDeclaredMethod("setValue", Any::class.java)
            flowField.type.getDeclaredMethod("compareAndSet", Any::class.java, Any::class.java)
            repository.declaredConstructors.filter {
                it.parameterTypes.getOrNull(1) == Int::class.javaPrimitiveType
            }.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    if (chain.args[1] == 0) safely("appearance repository") {
                        restoreNativeAppearance()
                        appearanceRepository = chain.thisObject
                        sampledAppearanceFlow = flowField.get(chain.thisObject)
                        windowPackage = null
                        updateDarkBar()
                    }
                    result
                }
            }
            module.hook(attributesChanged).intercept { chain ->
                if (chain.args[0] != 0 || repositoryField.get(chain.thisObject) !== appearanceRepository) {
                    return@intercept chain.proceed()
                }
                // An app owning the status bar must get its own attributes at once: stop forcing
                // the dark bar first, then let the app's attributes through.
                windowPackage = chain.args[6] as? String
                val result = chain.proceed()
                safely("window appearance handoff") { updateDarkBar() }
                result
            }
        }

        private fun restoreNativeAppearance() {
            val previous = publishedRegions ?: return
            val flow = sampledAppearanceFlow ?: return
            // Do not overwrite a newer value from Samsung's own region sampler.
            flow.reflect.call("compareAndSet", previous, nativeSampledRegions ?: emptyList<Any>())
            publishedRegions = null
            nativeSampledRegions = null
        }

        private fun updateDarkBar() {
            val flow = sampledAppearanceFlow ?: return
            if (!darkBarAllowed()) {
                // Samsung's transition controller synchronizes this input change with the
                // app animation, including when task and window callbacks arrive out of order.
                restoreNativeAppearance()
                return
            }
            val constructor = appearanceRegionConstructor ?: return
            // One region over the whole bar: APPEARANCE_LIGHT_STATUS_BARS (8) is Samsung's dark icons.
            val size = context.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }
            val desired = listOf(constructor.newInstance(8, Rect(0, 0, size, size)))
            val current = flow.reflect.call("getValue")
            if (current == desired) return
            if (publishedRegions == null || current != publishedRegions) nativeSampledRegions = current
            flow.reflect.call("setValue", desired)
            publishedRegions = desired
        }

        private fun proxy(type: Class<*>, callback: (String, Array<out Any?>?) -> Any?): Any =
            Proxy.newProxyInstance(loader, arrayOf(type)) { instance, method, args ->
                when (method.name) {
                    "equals" -> instance === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "OneUIX ${type.simpleName}"
                    else -> callback(method.name, args)
                }
            }
    }
}
