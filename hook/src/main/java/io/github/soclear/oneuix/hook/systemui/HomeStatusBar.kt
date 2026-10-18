package io.github.soclear.oneuix.hook.systemui

import android.animation.ValueAnimator
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
import android.view.animation.AccelerateDecelerateInterpolator
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Preference
import io.github.soclear.oneuix.common.StatusBarAppearance
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Uses SystemUI's own task and composition notifications. No launcher IPC or screen capture. */
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
        private val clocks = WeakHashMap<View, ClockFade>()
        private var stateController: Any? = null
        private var dispatcher: Any? = null
        private var statusView: View? = null
        private var homeActive = false
        private var tintActive = false
        private var deferNativeRestore = false
        private var homeTask = false
        private var ownTintUpdate = false
        private var nativeIntensity = 0f
        private var nativeAreas = arrayListOf<Rect>()
        private var tintOverridden = false
        private var lastAppliedIntensity: Float? = null
        private var lastAppliedAreas: List<Rect>? = null
        private var samplers = emptyList<Any>()
        private val regions = arrayOf(Rect(), Rect())
        private val darkBackground = arrayOfNulls<Boolean>(2)
        private var sampling = false
        private val reported = mutableSetOf<String>()
        private val refresh = Runnable { safely("home state") { refreshHome() } }
        // One-shot fallback only: native SystemUI tint callbacks normally complete the
        // transition sooner. Never poll window state or keep sampling outside Home/Recents.
        private val delayedTintRestore = Runnable {
            safely("native tint fallback") { restoreNativeTint() }
        }
        private var restorePending = false
        private val transitionAlpha = View::class.java.getDeclaredMethod("setTransitionAlpha", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        private val getTransitionAlpha = View::class.java.getDeclaredMethod("getTransitionAlpha")
            .apply { isAccessible = true }

        private data class ClockFade(val alpha: Float, val accessibility: Int,
            var hidden: Boolean = false, var animator: ValueAnimator? = null)

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
            if (options.adaptiveHomeStatusBar) safely("colour hooks") { installTintHooks() }
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
                homeTask || isRecents, interactive, locked, state, expanded, dozing || dreaming
            )
            deferNativeRestore = interactive && !locked && state == 0 &&
                !expanded && !dozing && !dreaming
            clocks.keys.toList().forEach { setClockHidden(it, homeActive) }
            if (options.adaptiveHomeStatusBar) {
                safely("sampling") { updateSampling() }
                safely("tint") { updateTint() }
            }
        }

        private fun trackClock(view: View) {
            if (view.display?.displayId != 0 || clocks.containsKey(view)) return
            clocks[view] = ClockFade((getTransitionAlpha.invoke(view) as Float), view.importantForAccessibility)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    clocks.remove(v)?.let {
                        it.animator?.cancel()
                        transitionAlpha.invoke(v, it.alpha)
                        v.importantForAccessibility = it.accessibility
                    }
                    v.removeOnAttachStateChangeListener(this)
                }
            })
            setClockHidden(view, homeActive)
            scheduleRefresh()
        }

        private fun setClockHidden(view: View, hidden: Boolean) {
            val fade = clocks[view] ?: return
            if (fade.hidden == hidden) return
            fade.hidden = hidden
            fade.animator?.cancel()
            view.importantForAccessibility = if (hidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO else fade.accessibility
            val target = if (hidden) 0f else fade.alpha
            if (!ValueAnimator.areAnimatorsEnabled() || !view.isShown) {
                transitionAlpha.invoke(view, target)
                fade.animator = null
                return
            }
            // Transition alpha multiplies the native alpha, preserving Samsung's own animations.
            fade.animator = ValueAnimator.ofFloat(getTransitionAlpha.invoke(view) as Float, target).apply {
                duration = 250L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { transitionAlpha.invoke(view, it.animatedValue as Float) }
                start()
            }
        }

        private fun trackStatusView(view: View) {
            if (view.display != null && view.display.displayId != 0) return
            if (statusView === view) return
            statusView?.let { old -> destroySamplers(old) }
            statusView = view
            // A newly bound status view must receive its first tint even if the value is unchanged.
            lastAppliedIntensity = null
            lastAppliedAreas = null
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
                        safely("tint restore") { updateTint() }
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
            val enabled = tintActive && view?.isAttachedToWindow == true && view.width > 0 && view.height > 0
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
                                "isSamplingEnabled" -> tintActive
                                "onRegionDarknessChanged" -> {
                                    if (sampling && tintActive && statusView === view) {
                                        darkBackground[index] = args?.firstOrNull() as? Boolean
                                        safely("sample tint") { updateTint() }
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

        private fun hasAdaptiveColours() = tintActive && sampling && darkBackground.all { it != null }
        private fun homeIntensity() = if (darkBackground.any { it == false }) 1f else 0f
        private fun homeAreas(): ArrayList<Rect> = ArrayList(regions.indices.filter {
            darkBackground[it] == false
        }.map { index -> Rect(regions[index]).apply { top = 0 } })

        private fun installTintHooks() {
            val type = loader.loadClass("com.android.systemui.statusbar.phone.DarkIconDispatcherImpl")
            val apply = type.getDeclaredMethod("applyDarkIntensity", Float::class.javaPrimitiveType)
            val setAreas = type.getDeclaredMethod("setIconsDarkArea", ArrayList::class.java)
            val clearAreas = type.getDeclaredMethod("clearIconsDarkArea")
            type.getDeclaredField("mTintAreas") // Validate before installing hooks.
            type.declaredConstructors.forEach { constructor ->
                module.hook(constructor).intercept { chain ->
                    val result = chain.proceed()
                    if (chain.args.firstOrNull() == 0) {
                        dispatcher = chain.thisObject
                        nativeIntensity = (dispatcher?.reflect?.get("mDarkIntensity") as? Float) ?: 0f
                        scheduleRefresh()
                    }
                    result
                }
            }
            module.hook(apply).intercept { chain ->
                if (chain.thisObject !== dispatcher || ownTintUpdate) return@intercept chain.proceed()
                nativeIntensity = chain.args[0] as Float
                if (!hasAdaptiveColours()) {
                    val result = chain.proceed()
                    // A real SystemUI intensity event supersedes our previous sampled tint.
                    // Avoid restoring a stale Home value during a window transition.
                    if (!tintActive && tintOverridden) {
                        cancelPendingRestore()
                        tintOverridden = false
                        lastAppliedIntensity = null
                        lastAppliedAreas = null
                    }
                    return@intercept result
                }
                val args = chain.args.toTypedArray()
                args[0] = homeIntensity()
                chain.proceed(args)
            }
            module.hook(setAreas).intercept { chain ->
                if (chain.thisObject !== dispatcher || ownTintUpdate) return@intercept chain.proceed()
                nativeAreas = ArrayList((chain.args[0] as? List<*>)?.filterIsInstance<Rect>()?.map { Rect(it) }.orEmpty())
                if (!hasAdaptiveColours()) return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = homeAreas()
                chain.proceed(args)
            }
            module.hook(clearAreas).intercept { chain ->
                if (chain.thisObject !== dispatcher || ownTintUpdate) return@intercept chain.proceed()
                nativeAreas.clear()
                if (hasAdaptiveColours()) {
                    // The native clear is a transient intermediate state before the next
                    // app-provided tint. Running it while the override is active briefly
                    // removes the custom tint regions and can blank the icons for a frame.
                    // Keep our tint in place; nativeRegions remains empty for handoff.
                    null
                } else {
                    chain.proceed()
                }
            }
        }

        private fun cancelPendingRestore() {
            main.removeCallbacks(delayedTintRestore)
            restorePending = false
        }

        private fun restoreNativeTint() {
            cancelPendingRestore()
            if (!tintOverridden || hasAdaptiveColours()) return
            val target = dispatcher ?: return
            ownTintUpdate = true
            try {
                target.reflect.call("setIconsDarkArea", ArrayList(nativeAreas))
                target.reflect.call("applyDarkIntensity", nativeIntensity)
                tintOverridden = false
                lastAppliedIntensity = null
                lastAppliedAreas = null
            } finally { ownTintUpdate = false }
        }

        private fun updateTint() {
            val target = dispatcher ?: return
            if (!hasAdaptiveColours()) {
                if (!tintOverridden) return
                if (tintActive && sampling) {
                    // Keep the last tint until the next region sample arrives.
                    cancelPendingRestore()
                } else if (deferNativeRestore) {
                    // Samsung can detach the status view while launching an app. Keep the
                    // last tint through that gap instead of briefly restoring a stale
                    // native colour. Samsung's next tint callback still takes precedence;
                    // the single delayed restore is only a bounded fallback.
                    if (!restorePending) {
                        restorePending = true
                        main.postDelayed(delayedTintRestore, 300L)
                    }
                } else {
                    // Lockscreen, shade, screen off and detached views restore immediately.
                    restoreNativeTint()
                }
                return
            }
            cancelPendingRestore()
            val desiredIntensity = homeIntensity()
            val desiredAreas = homeAreas()
            if (!StatusBarAppearance.needsTintUpdate(
                    tintOverridden, lastAppliedIntensity, desiredIntensity,
                    lastAppliedAreas == desiredAreas
                )) return
            ownTintUpdate = true
            try {
                target.reflect.call("setIconsDarkArea", desiredAreas)
                target.reflect.call("applyDarkIntensity", desiredIntensity)
                tintOverridden = true
                lastAppliedIntensity = desiredIntensity
                lastAppliedAreas = desiredAreas.map(::Rect)
            } finally { ownTintUpdate = false }
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
