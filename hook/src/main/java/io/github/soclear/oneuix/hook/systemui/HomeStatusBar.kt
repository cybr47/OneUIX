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
import java.util.concurrent.Executors

/** Uses native task/composition notifications and the launcher's surface events. */
@SuppressLint("PrivateApi")
internal object HomeStatusBar {
    context(module: XposedModule)
    fun install(options: Preference.SystemUI.StatusBar) = afterAttachTry(
        options.adaptiveHomeStatusBar || options.hideClockOnHome
    ) {
        Controller(this, module, options).install()
    }

    private class Controller(
        private val context: Context,
        private val module: XposedModule,
        private val options: Preference.SystemUI.StatusBar,
    ) {
        private val loader = context.classLoader
        private val main = Handler(Looper.getMainLooper())
        private val background by lazy {
            Executors.newSingleThreadExecutor { Thread(it, "OneUIX-status-sampling").apply { isDaemon = true } }
        }
        private val clocks = WeakHashMap<View, HomeClockTransition>()
        private var stateController: Any? = null
        private var appearanceRepository: Any? = null
        private var sampledAppearanceFlow: Any? = null
        private var appearanceRegionConstructor: Constructor<*>? = null
        private var nativeSampledRegions: Any? = null
        private var publishedRegions: List<Any>? = null
        private var windowPackage: String? = null
        private var statusView: View? = null
        private var homeActive = false
        private var tintActive = false
        private var homeTask = false
        private var recentsTask = false
        private var nativeLauncherAppearance: Boolean? = null
        private var samplers = emptyList<Any>()
        private val regions = arrayOf(Rect(), Rect())
        private val darkBackground = arrayOfNulls<Boolean>(2)
        private var sampling = false
        private val reported = mutableSetOf<String>()
        private val refresh = Runnable { safely("home state") { refreshHome() } }

        private fun safely(part: String, action: () -> Unit) {
            try { action() } catch (t: Throwable) {
                if (reported.add(part)) with(module) { xlog("Home status bar: $part unavailable", t) }
            }
        }

        private fun scheduleRefresh() {
            // Binder task callbacks can arrive in bursts. Do not poll or queue one job per event.
            main.removeCallbacks(refresh)
            main.post(refresh)
        }

        fun install() {
            val taskClass = loader.loadClass("com.android.systemui.shared.system.TaskStackChangeListeners\$Impl")
            val stateClass = loader.loadClass("com.android.systemui.statusbar.StatusBarStateControllerImpl")
            val binder = loader.loadClass("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl")
            val clockClass = loader.loadClass("com.android.systemui.statusbar.policy.QSClockIndicatorView")
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
                                    if (name.startsWith("on")) scheduleRefresh()
                                    null
                                }
                                addListener.invoke(instance, listener)
                                scheduleRefresh()
                            }
                        }
                    }
                    result
                }
            }
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
            if (options.adaptiveHomeStatusBar) {
                safely("colour hooks") { installAppearanceHooks() }
                safely("launcher state") {
                    LauncherStatusBarBridge.listenForState(context, main) { nativeAppearance ->
                        nativeLauncherAppearance = nativeAppearance
                        safely("launcher handoff") {
                            updateSampling()
                            updateAppearance()
                        }
                        scheduleRefresh()
                    }
                    // Also works when only SystemUI restarts in the drawer or Recents.
                    LauncherStatusBarBridge.query(context)
                }
            }
            module.hook(binder.declaredMethods.single { it.name == "bind" }).intercept { chain ->
                val result = chain.proceed()
                val view = chain.args.firstOrNull() as? View
                if (view != null) safely("status view") { trackStatusView(view) }
                result
            }
            if (options.hideClockOnHome) {
                module.hook(clockClass.getDeclaredMethod("onAttachedToWindow")).intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view -> safely("clock") { trackClock(view) } }
                    result
                }
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

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission") // Runs inside the already-privileged SystemUI process.
        private fun refreshHome() {
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
            val controller = stateController
            val state = runCatching { controller?.reflect?.call("getState") as? Int }.getOrNull()
            val expanded = runCatching { controller?.reflect?.call("isExpanded") as? Boolean }.getOrNull() ?: true
            val dozing = runCatching { controller?.reflect?.call("isDozing") as? Boolean }.getOrNull() ?: true
            val dreaming = runCatching { controller?.reflect?.call("isDreaming") as? Boolean }.getOrNull() ?: true
            val interactive = context.getSystemService(PowerManager::class.java).isInteractive
            val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            homeActive = StatusBarAppearance.isHomeAppearanceActive(
                homeTask, interactive, locked, state, expanded, dozing || dreaming
            )
            tintActive = StatusBarAppearance.isHomeAppearanceActive(
                homeTask && !isRecents, interactive, locked, state, expanded, dozing || dreaming
            )
            clocks.values.toList().forEach { it.setHidden(homeActive) }
            if (options.adaptiveHomeStatusBar) {
                safely("sampling") { updateSampling() }
                safely("tint") { updateAppearance() }
            }
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
            clocks[view]?.setHidden(homeActive)
            scheduleRefresh()
        }

        private fun trackStatusView(view: View) {
            if (view.display != null && view.display.displayId != 0) return
            if (statusView === view) return
            statusView?.let { old -> destroySamplers(old) }
            statusView = view
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    statusView = v
                    scheduleRefresh()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    // RegionSamplingHelper owns and destroys its native listener on detach.
                    if (statusView === v) {
                        samplers = emptyList()
                        sampling = false
                        darkBackground.fill(null)
                        statusView = null
                        safely("tint restore") { updateAppearance() }
                    }
                }
            })
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleRefresh() }
            scheduleRefresh()
        }

        private fun destroySamplers(view: View) {
            samplers.forEach { helper -> safely("sampler cleanup") {
                helper.reflect.call("onViewDetachedFromWindow", view)
            } }
            samplers = emptyList()
            sampling = false
            darkBackground.fill(null)
        }

        private fun updateSampling() {
            val view = statusView
            val enabled = tintActive && windowAllowsSampling() && sampledAppearanceFlow != null &&
                view?.isAttachedToWindow == true && view.width > 0 && view.height > 0
            if (!enabled) {
                if (sampling) samplers.forEach { it.reflect.call("stop") }
                sampling = false
                darkBackground.fill(null)
                return
            }
            view!!
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = location[0]
            val y = location[1]
            val middle = x + view.width / 2
            val newRegions = arrayOf(Rect(x, y, middle, y + view.height), Rect(middle, y, x + view.width, y + view.height))
            val changed = !regions.contentEquals(newRegions)
            regions.indices.forEach { regions[it].set(newRegions[it]) }
            if (samplers.isEmpty()) {
                val helperClass = loader.loadClass("com.android.wm.shell.shared.handles.RegionSamplingHelper")
                val callback = loader.loadClass("com.android.wm.shell.shared.handles.RegionSamplingHelper\$SamplingCallback")
                val constructor = helperClass.getConstructor(View::class.java, callback,
                    java.util.concurrent.Executor::class.java, java.util.concurrent.Executor::class.java)
                val created = mutableListOf<Any>()
                try {
                    regions.indices.forEach { index ->
                        val listener = proxy(callback) { name, args ->
                            when (name) {
                                "getSampledRegion" -> Rect(regions[index])
                                "isSamplingEnabled" -> tintActive && windowAllowsSampling()
                                "onRegionDarknessChanged" -> {
                                    if (sampling && tintActive && windowAllowsSampling() && statusView === view) {
                                        darkBackground[index] = args?.firstOrNull() as? Boolean
                                        safely("sample tint") { updateAppearance() }
                                    }
                                    null
                                }
                                else -> null
                            }
                        }
                        created += constructor.newInstance(view, listener, context.mainExecutor, background)
                    }
                    samplers = created
                } catch (t: Throwable) {
                    created.forEach { it.reflect.call("onViewDetachedFromWindow", view) }
                    throw t
                }
            }
            if (!sampling || changed) {
                darkBackground.fill(null)
                sampling = true
                samplers.forEachIndexed { index, helper ->
                    helper.reflect["mWindowVisible"] = true
                    helper.reflect.call("start", Rect(regions[index]))
                }
            }
        }

        private fun windowAllowsSampling() =
            StatusBarAppearance.windowAllowsAdaptiveTint(windowPackage, recentsTask) &&
                StatusBarAppearance.launcherAllowsAdaptiveTint(nativeLauncherAppearance, recentsTask)

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
            // Validate the full contract before enabling sampling or installing any hook.
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
                        scheduleRefresh()
                    }
                    result
                }
            }
            module.hook(attributesChanged).intercept { chain ->
                if (chain.args[0] != 0 || repositoryField.get(chain.thisObject) !== appearanceRepository) {
                    return@intercept chain.proceed()
                }
                // This main-thread window event can precede the task-stack notification.
                // Stop accepting Home samples as soon as an app owns the status bar.
                windowPackage = chain.args[6] as? String
                val result = chain.proceed()
                safely("window appearance handoff") {
                    if (!windowAllowsSampling()) {
                        updateSampling()
                        // Forward the new app attributes FIRST. Clearing the sample input
                        // now exposes those attributes, never a cached Home tint.
                        restoreNativeAppearance()
                    }
                }
                scheduleRefresh()
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

        private fun updateAppearance() {
            val flow = sampledAppearanceFlow ?: return
            if (!tintActive || !windowAllowsSampling()) {
                // Samsung's transition controller synchronizes this input change with the
                // app animation, including when task and window callbacks arrive out of order.
                restoreNativeAppearance()
                return
            }
            // Preserve the current input during a view rebind or until BOTH sides are ready.
            if (!sampling || darkBackground.any { it == null }) return
            val constructor = appearanceRegionConstructor ?: return
            val desired = regions.indices.map { index ->
                constructor.newInstance(
                    if (darkBackground[index] == false) 8 else 0, // APPEARANCE_LIGHT_STATUS_BARS
                    Rect(regions[index]).apply { top = 0 }
                )
            }
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
