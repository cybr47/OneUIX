package io.github.soclear.oneuix.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.common.BuildConfig
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.common.StatusBarAppearance
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@SuppressLint("PrivateApi")
object StatusBar {
    context(xposedModule: XposedModule)
    fun setStatusBarPaddingDp(left: Float?, right: Float?) = afterAttachTry(left != null || right != null) {
        val clazz =
            classLoader.loadClass("com.android.systemui.statusbar.phone.IndicatorGardenAlgorithmCenterCutout")
        if (left != null) {
            val method = clazz.getDeclaredMethod("calculateLeftPadding")
            xposedModule.hook(method).intercept { chain ->
                val inputProperties = chain.thisObject.reflect["inputProperties"]
                val density = inputProperties?.reflect?.get("density") as? Float ?: 1f
                (left * density).roundToInt()
            }
        }
        if (right != null) {
            val method = clazz.getDeclaredMethod("calculateRightPadding")
            xposedModule.hook(method).intercept { chain ->
                val inputProperties = chain.thisObject.reflect["inputProperties"]
                val density = inputProperties?.reflect?.get("density") as? Float ?: 1f
                (right * density).roundToInt()
            }
        }
    }

    context(xposedModule: XposedModule)
    fun setBatteryIconScale(
        widthScale: Float?,
        heightScale: Float?
    ) = afterAttachTry(widthScale != null || heightScale != null) {
        val clazz = classLoader.loadClass("com.android.systemui.battery.BatteryMeterView")
        val method = clazz.getDeclaredMethod("scaleBatteryMeterViewsLegacy")
        xposedModule.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val mBatteryIconView = chain.thisObject.reflect["mBatteryIconView"] as? ImageView
                if (mBatteryIconView != null) {
                    mBatteryIconView.layoutParams = mBatteryIconView.layoutParams.apply {
                        if (widthScale != null) {
                            width = (width * widthScale).roundToInt()
                        }
                        if (heightScale != null) {
                            height = (height * heightScale).roundToInt()
                        }
                    }
                }
            } catch (t: Throwable) {
                xlog(t)
            }
            result
        }
    }


    context(xposedModule: XposedModule)
    fun hideBatteryPercentageSign() = afterAttachTry(Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val batterMeterFormat = "status_bar_settings_${
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE) "uniform_"
            else ""
        }battery_meter_format"

        @SuppressLint("DiscouragedApi")
        val targetId = resources.getIdentifier(batterMeterFormat, "string", Package.SYSTEMUI)
        if (targetId != 0) {
            val resourcesClass = classLoader.loadClass("android.content.res.Resources")
            resourcesClass.declaredMethods
                .filter { it.name == "getString" && it.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType }
                .forEach { method ->
                    xposedModule.hook(method).intercept { chain ->
                        if (chain.args.firstOrNull() == targetId) "%d" else chain.proceed()
                    }
                }
        }
    }

    context(xposedModule: XposedModule)
    fun updateStatusBarClockEverySecond() = afterAttachTry {
        // 每秒更新
        try {
            val helperClass = classLoader.loadClass(
                "com.android.systemui.statusbar.policy.QSClockQuickStarHelper"
            )
            val method = helperClass.getDeclaredMethod("updateSecondsClockHandler")
            xposedModule.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val mSecondsHandler = chain.thisObject.reflect["mSecondsHandler"]
                    if (mSecondsHandler == null) {
                        val looper = Looper.myLooper()
                        if (looper != null) {
                            val handler = Handler(looper)
                            chain.thisObject.reflect["mSecondsHandler"] = handler
                            val mSecondTick = chain.thisObject.reflect["mSecondTick"] as? Runnable
                            if (mSecondTick != null) {
                                handler.post(mSecondTick)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    xlog(t)
                }
                result
            }
        } catch (t: Throwable) {
            xlog(t)
        }

        // 数字字体等宽
        try {
            val controllerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.policy.QSClockIndicatorViewController"
            )
            val onViewAttachedMethod = controllerClass.getDeclaredMethod("onViewAttached")
            xposedModule.hook(onViewAttachedMethod).intercept { chain ->
                val result = chain.proceed()
                try {
                    val clockTextView = chain.thisObject.reflect["view"] as? TextView
                    clockTextView?.fontFeatureSettings = "tnum"
                } catch (t: Throwable) {
                    xlog(t)
                }
                result
            }
        } catch (t: Throwable) {
            xlog(t)
        }
    }

    context(xposedModule: XposedModule)
    fun setStatusBarClockTextScale(scale: Float) = afterAttachTry {
        // One UI 9: onDensityOrFontScaleChanged() only posts a Runnable that finally calls
        // setTextSize(COMPLEX_UNIT_PX, clockFontSize), overwriting anything set earlier.
        // Scale the size in that Runnable instead, from its own base value (no compounding).
        val runnableClass = classLoader.loadClass(
            "com.android.systemui.statusbar.policy.QSClockIndicatorViewController" +
                "\$onDensityOrFontScaleChanged\$1\$1"
        )
        val viewField = runnableClass.getDeclaredField("\$this_apply").apply { isAccessible = true }
        val sizeField = runnableClass.getDeclaredField("\$clockFontSize").apply { isAccessible = true }
        val method = runnableClass.getDeclaredMethod("run")
        xposedModule.hook(method).intercept { chain ->
            val result = chain.proceed()
            try {
                val clockView = viewField.get(chain.thisObject) as? TextView
                val baseSize = sizeField.getInt(chain.thisObject)
                clockView?.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSize * scale)
            } catch (t: Throwable) {
                xlog(t)
            }
            result
        }
    }

    context(xposedModule: XposedModule)
    fun setStatusBarClockFormat(format: String) {
        val dateTimeFormatter = try {
            DateTimeFormatter.ofPattern(format)
        } catch (_: Throwable) {
            DateTimeFormatter.ofPattern("HH:mm")
        }
        setStatusBarClockText {
            dateTimeFormatter.format(LocalDateTime.now())
        }
    }

    context(xposedModule: XposedModule)
    private fun setStatusBarClockText(block: () -> String) = afterAttachTry {
        val clockClass = classLoader.loadClass(
            "com.android.systemui.statusbar.policy.QSClockIndicatorView"
        )
        val qsClockBellSoundClass = classLoader.loadClass(
            "com.android.systemui.statusbar.policy.QSClockBellSound"
        )
        val method = clockClass.getDeclaredMethod("notifyTimeChanged", qsClockBellSoundClass)
        xposedModule.hook(method).intercept { chain ->
            val clockTextView = chain.thisObject as? TextView
            val dateTime = block()
            clockTextView?.text = dateTime
            clockTextView?.contentDescription = dateTime
            null
        }
    }

    context(xposedModule: XposedModule)
    fun hideSecureFolderStatusBarIcon() = afterAttachTry {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val controllerImplClass = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"
            )
            val holderClass = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.StatusBarIconHolder"
            )
            val setIconMethod = controllerImplClass.getDeclaredMethod(
                "setIcon",
                String::class.java,
                holderClass
            )
            xposedModule.hook(setIconMethod).intercept { chain ->
                val slot = chain.args[0] as? String
                if (slot == "secure_folder") {
                    null
                } else {
                    chain.proceed()
                }
            }
        } else {
            val controllerImplClass = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.StatusBarIconControllerImpl"
            )
            val setIconMethod = controllerImplClass.getDeclaredMethod(
                "setIcon",
                String::class.java,
                Int::class.javaPrimitiveType,
                CharSequence::class.java
            )
            xposedModule.hook(setIconMethod).intercept { chain ->
                val slot = chain.args[0] as? String
                if (slot == "secure_folder") {
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    context(xposedModule: XposedModule)
    fun restoreBluetoothStatusBarIcon() = afterAttachTry {
        val controllerImplClass = classLoader.loadClass(
            "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"
        )
        val iconManagerClass = classLoader.loadClass(
            "com.android.systemui.statusbar.phone.ui.IconManager"
        )
        val method = controllerImplClass.getDeclaredMethod(
            "hideBySimplification",
            iconManagerClass,
            String::class.java
        )
        xposedModule.hook(method).intercept { chain ->
            val slot = chain.args.getOrNull(1) as? String
            if (slot == "bluetooth" || slot == "bluetooth_connected") {
                false
            } else {
                chain.proceed()
            }
        }
    }

    context(xposedModule: XposedModule)
    fun doubleTapStatusBarToSleep() {
        var lastTapTime = 0L

        fun lockScreen(context: Context) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            powerManager?.reflect?.call("goToSleep", SystemClock.uptimeMillis())
        }

        afterAttachTry {
            val viewClass = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView"
            )
            val method = viewClass.getDeclaredMethod("onTouchEvent", MotionEvent::class.java)
            xposedModule.hook(method).intercept { chain ->
                val event = chain.args[0] as MotionEvent
                if (event.action != MotionEvent.ACTION_DOWN) {
                    chain.proceed()
                } else {
                    val currentTime = System.nanoTime()
                    val interval = currentTime - lastTapTime
                    if (interval in 40_000_000L..300_000_000L) {
                        lastTapTime = 0L
                        val view = chain.thisObject as View
                        lockScreen(view.context)
                        true
                    } else {
                        lastTapTime = currentTime
                        chain.proceed()
                    }
                }
            }
        }
    }

    private fun findLockscreenStatusBarDescendant(root: View, className: String): View? {
        if (root.javaClass.name == className) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findLockscreenStatusBarDescendant(root.getChildAt(index), className)?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun findLockscreenStatusBarViews(
        root: View,
        predicate: (View) -> Boolean,
    ): List<View> {
        val found = mutableListOf<View>()
        fun visit(view: View) {
            if (predicate(view)) found += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return found
    }

    private fun lockscreenEntryName(view: View): String? =
        if (view.id == View.NO_ID) null
        else runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    /** Outermost views under [root] (never [root] itself) that are the given lock screen part. */
    private fun findLockscreenParts(root: View, part: StatusBarAppearance.LockscreenPart): List<View> {
        val found = mutableListOf<View>()
        fun visit(view: View) {
            if (view !== root &&
                StatusBarAppearance.lockscreenPart(lockscreenEntryName(view), view.javaClass.name) == part
            ) {
                found += view
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)

        // One UI 9's battery is hosted by a Compose-icon view that can be a sibling of the
        // view returned by HomeStatusBarViewBinderImpl.bind(), rather than a descendant of it.
        // Resolve Samsung's dedicated battery host ID in the current SystemUI resources and
        // search the same window before giving up. This is deliberately battery-only: searching
        // the whole window for generic Compose views could hide unrelated status-bar icons.
        if (part == StatusBarAppearance.LockscreenPart.BATTERY) {
            // Always add the dedicated One UI 9 Compose battery host, even if a legacy
            // BatteryMeterView was found in this subtree. That legacy match may not be the
            // battery actually rendered in the status bar.
            val batteryHostId = root.resources.getIdentifier(
                "battery_meter_composable_view", "id", Package.SYSTEMUI
            )
            if (batteryHostId != 0) {
                fun visitWindow(view: View) {
                    if (view.id == batteryHostId) {
                        found += view
                        return
                    }
                    if (view is ViewGroup) {
                        for (index in 0 until view.childCount) visitWindow(view.getChildAt(index))
                    }
                }
                visitWindow(root.rootView)
            }
        }
        return found.distinct()
    }

    /** Debug builds only: the bar's views plus matches elsewhere in its window. */
    private fun describeLockscreenViews(bar: View): String = buildString {
        append("Lock screen status bar views:")
        var lines = 0
        fun visit(view: View, depth: Int) {
            if (lines++ >= 150) return
            append('\n').append("  ".repeat(depth)).append(lockscreenEntryName(view) ?: "-")
                .append(" : ").append(view.javaClass.name).append(" vis=").append(view.visibility)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
            }
        }
        visit(bar, 0)
        append("\nNotification/battery matches in the whole window:")
        findLockscreenStatusBarViews(bar.rootView) {
            StatusBarAppearance.lockscreenPart(lockscreenEntryName(it), it.javaClass.name) != null
        }.take(30).forEach { match ->
            append('\n').append("  ").append(lockscreenEntryName(match) ?: "-")
                .append(" : ").append(match.javaClass.name).append(" in ")
            var parent: Any? = match.parent
            var depth = 0
            while (parent is View && depth++ < 6) {
                append(parent.javaClass.simpleName).append('<')
                parent = parent.parent
            }
        }
    }

    private fun isLockscreenConnectivitySlot(slot: String): Boolean {
        val normalized = slot.lowercase()
        return listOf(
            "bluetooth", "wifi", "wlan", "mobile", "cellular", "signal", "airplane",
            "ethernet", "hotspot", "tethering", "vpn", "volte", "vonr", "vowifi",
            "hd_voice", "voice_over_lte", "5g", "4g", "3g", "lte", "roaming",
            "no_sim", "sim_card", "data_saver"
        ).any { token -> normalized.contains(token) }
    }

    private fun hideLockscreenConnectivityIconSlots(root: View) {
        // StatusBarIconView exposes its Samsung/SystemUI slot through getSlot(). Filter
        // those individually to keep non-connectivity indicators (for example alarms).
        val slot = runCatching {
            root.javaClass.getMethod("getSlot").invoke(root) as? String
        }.getOrNull()
        if (slot != null) {
            if (isLockscreenConnectivitySlot(slot)) root.visibility = View.GONE
            return
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                hideLockscreenConnectivityIconSlots(root.getChildAt(index))
            }
        }
    }

    /**
     * The lock screen battery is a ComposeView that KeyguardStatusBarViewController.onViewAttached
     * passes to SamsungBatteryViewBinder.bind (or UnifiedBatteryViewBinder.bind). The view has no
     * field or reliable id, so it is taken from that call and kept hidden from then on. The same
     * binders also serve Home and Quick Settings, so only calls made inside onViewAttached count.
     */
    private fun hideKeyguardBatteryView(classLoader: ClassLoader, xposedModule: XposedModule) {
        val inKeyguardAttach = ThreadLocal<Boolean>()
        val controller = classLoader.loadClass(
            "com.android.systemui.statusbar.phone.KeyguardStatusBarViewController"
        )
        xposedModule.hook(controller.getDeclaredMethod("onViewAttached")).intercept { chain ->
            inKeyguardAttach.set(true)
            try {
                chain.proceed()
            } finally {
                inKeyguardAttach.set(false)
            }
        }
        listOf(
            "com.android.systemui.battery.SamsungBatteryViewBinder",
            "com.android.systemui.statusbar.pipeline.battery.ui.binder.UnifiedBatteryViewBinder",
        ).forEach { name ->
            val binder = runCatching { classLoader.loadClass(name) }.getOrNull() ?: return@forEach
            binder.declaredMethods.filter { it.name == "bind" }.forEach { method ->
                xposedModule.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (inKeyguardAttach.get() == true) {
                        (chain.args.getOrNull(0) as? View)?.let(::keepHidden)
                    }
                    result
                }
            }
        }
    }

    /** Hides [view] and hides it again whenever native code shows it. Removed on detach. */
    private val keptHidden: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun keepHidden(view: View) {
        if (!keptHidden.add(view)) return
        val listener = object : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
            private var observer: ViewTreeObserver? = null
            override fun onPreDraw(): Boolean {
                if (view.visibility != View.GONE) view.visibility = View.GONE
                return true
            }

            override fun onViewAttachedToWindow(v: View) {
                if (observer == null) observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            }

            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = null
            }
        }
        view.visibility = View.GONE
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
    }

    context(xposedModule: XposedModule)
    fun customizeLockscreenStatusBar(
        hideCarrierName: Boolean,
        hideNotificationIcons: Boolean,
        hideConnectivityIcons: Boolean,
        hideBatteryIcon: Boolean,
    ) = afterAttachTry(
        hideCarrierName || hideNotificationIcons || hideConnectivityIcons || hideBatteryIcon
    ) {
        val viewClass = classLoader.loadClass(
            "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
        )
        var dumped = false

        fun applyOptions(view: View) {
            if (hideCarrierName) {
                val carrier = runCatching {
                    view.reflect["mCarrierLabel"] as? View
                }.getOrNull() ?: findLockscreenStatusBarDescendant(
                    view, "com.android.keyguard.CarrierText"
                )
                // The carrier label sits in its own wrapper. Hide the wrapper as well so
                // notification icons can use the space immediately to its left.
                (carrier?.parent as? View)?.visibility = View.GONE
            }

            if (BuildConfig.DEBUG && !dumped) {
                dumped = true
                xlog(describeLockscreenViews(view), priority = Log.INFO)
            }

            if (hideNotificationIcons) {
                // Match by view id or class name: Samsung does not always use AOSP's exact
                // class. The outermost match is hidden so the whole icon area leaves the
                // layout. If the bar has none, try the status bar's own notification area
                // elsewhere in this window, then individual icon views.
                val icons = findLockscreenParts(view, StatusBarAppearance.LockscreenPart.NOTIFICATION_ICONS)
                    .ifEmpty {
                        findLockscreenStatusBarViews(view.rootView) {
                            lockscreenEntryName(it)?.contains("notification_icon_area") == true
                        }
                    }
                    .ifEmpty {
                        findLockscreenStatusBarViews(view) {
                            it.javaClass.name.endsWith(".StatusBarIconView") ||
                                it.javaClass.name.endsWith(".NotificationIconView")
                        }
                    }
                icons.forEach { it.visibility = View.GONE }
            }

            if (hideConnectivityIcons) {
                findLockscreenStatusBarDescendant(
                    view, "com.android.systemui.statusbar.phone.StatusIconContainer"
                )?.let(::hideLockscreenConnectivityIconSlots)
            }

            if (hideBatteryIcon) {
                // One UI 9 replaces the legacy battery view with a Compose-based one that can be
                // hosted in a generic view, so match on its id as well as its class name.
                findLockscreenParts(view, StatusBarAppearance.LockscreenPart.BATTERY)
                    .forEach { it.visibility = View.GONE }
            }
        }

        if (hideBatteryIcon) hideKeyguardBatteryView(classLoader, xposedModule)

        val methods = listOf(
            viewClass.getDeclaredMethod("onFinishInflate"),
            viewClass.getDeclaredMethod("updateVisibilities"),
            viewClass.getDeclaredMethod(
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        )
        val hidden = buildSet {
            if (hideCarrierName) add(StatusBarAppearance.LockscreenPart.CARRIER)
            if (hideNotificationIcons) add(StatusBarAppearance.LockscreenPart.NOTIFICATION_ICONS)
            if (hideConnectivityIcons) add(StatusBarAppearance.LockscreenPart.CONNECTIVITY_ICONS)
            if (hideBatteryIcon) add(StatusBarAppearance.LockscreenPart.BATTERY)
        }
        fun parts(onlyWhileLocked: Boolean) = LockscreenStatusBarParts(
            hidden = hidden,
            onlyWhileLocked = onlyWhileLocked,
            isConnectivitySlot = { isLockscreenConnectivitySlot(it) },
            report = { xlog(it) },
            dump = { xlog(it, priority = Log.INFO) },
        )
        // KeyguardStatusBarView exists only on the lock screen. Native code can re-show a part
        // after layout, so the choice is also enforced before each draw.
        val keyguardParts = parts(onlyWhileLocked = false)
        methods.distinct().forEach { method ->
            xposedModule.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    (chain.thisObject as? View)?.let {
                        applyOptions(it)
                        keyguardParts.track(it)
                    }
                } catch (t: Throwable) {
                    xlog(t)
                }
                result
            }
        }

        // On this One UI 9 build, the notification icon area can be inflated outside the
        // KeyguardStatusBarView/HomeStatusBarView subtree. The APK's notification_icon_area.xml
        // identifies its dedicated NotificationIconContainer by notification_icon_container.
        // Track that exact container's parent as well, so the toggle works across either layout
        // without hiding notification icons in the shade/shelf or unrelated icon views.
        if (hideNotificationIcons) runCatching {
            val notificationContainerClass = classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer"
            )
            val notificationContainerId = resources.getIdentifier(
                "notification_icon_container", "id", Package.SYSTEMUI
            )
            val notificationParts = LockscreenStatusBarParts(
                hidden = setOf(StatusBarAppearance.LockscreenPart.NOTIFICATION_ICONS),
                onlyWhileLocked = true,
                isConnectivitySlot = { isLockscreenConnectivitySlot(it) },
                report = { xlog(it) },
                dump = { xlog(it, priority = Log.INFO) },
            )
            val onLayout = notificationContainerClass.getDeclaredMethod(
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            xposedModule.hook(onLayout).intercept { chain ->
                val result = chain.proceed()
                val container = chain.thisObject as? View
                if (notificationContainerId != 0 && container?.id == notificationContainerId) {
                    (container.parent as? View)?.let(notificationParts::track)
                }
                result
            }
        }.onFailure { xlog(it) }

        // The Compose battery host can be outside both KeyguardStatusBarView and the
        // HomeStatusBarViewBinder view tree. Hook the host's own layout instead of relying on
        // finding it through either tree, then track its parent so visibility is restored on unlock.
        if (hideBatteryIcon) runCatching {
            val batteryHostClass = classLoader.loadClass(
                "com.android.systemui.statusbar.pipeline.shared.ui.view.SingleBindableStatusBarComposeIconView"
            )
            val batteryHostId = resources.getIdentifier(
                "battery_meter_composable_view", "id", Package.SYSTEMUI
            )
            if (batteryHostId != 0) {
                val layoutSignature = arrayOf(
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                val onLayout = generateSequence(batteryHostClass as Class<*>?) { it.superclass }
                    .mapNotNull { owner ->
                        runCatching { owner.getDeclaredMethod("onLayout", *layoutSignature) }.getOrNull()
                    }
                    .firstOrNull() ?: error("No onLayout method found for Compose status-bar icon")
                val batteryParts = LockscreenStatusBarParts(
                    hidden = setOf(StatusBarAppearance.LockscreenPart.BATTERY),
                    onlyWhileLocked = true,
                    isConnectivitySlot = { isLockscreenConnectivitySlot(it) },
                    report = { xlog(it) },
                    dump = { xlog(it, priority = Log.INFO) },
                )
                xposedModule.hook(onLayout).intercept { chain ->
                    val result = chain.proceed()
                    val host = chain.thisObject as? View
                    if (host != null && batteryHostClass.isInstance(host) &&
                        host.id == batteryHostId
                    ) {
                        (host.parent as? View)?.let(batteryParts::track)
                    }
                    result
                }
            } else {
                if (BuildConfig.DEBUG) xlog("Lockscreen battery host ID battery_meter_composable_view was not found")
            }
        }.onFailure { xlog(it) }

        // One UI 9 can also draw the lock screen's carrier, notification icons and battery in the
        // status bar view that Home uses. Hide the same parts there, but only while the keyguard is
        // locked: they are restored as soon as it is not, so Home and apps are unaffected.
        runCatching {
            val binder = classLoader.loadClass(
                "com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinderImpl"
            )
            val homeParts = parts(onlyWhileLocked = true)
            xposedModule.hook(binder.declaredMethods.single { it.name == "bind" }).intercept { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? View)
                    ?.takeIf { it.display?.displayId == 0 }
                    ?.let(homeParts::track)
                result
            }
        }.onFailure { xlog(it) }
    }

    context(xposedModule: XposedModule)
    fun hideLockscreenStatusBar() = afterAttachTry {
        val viewClass = classLoader.loadClass(
            "com.android.systemui.statusbar.phone.KeyguardStatusBarView"
        )
        val method = viewClass.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
        xposedModule.hook(method).intercept { chain ->
            val newArgs = chain.args.toTypedArray()
            newArgs[0] = View.GONE
            chain.proceed(newArgs)
        }
    }

    context(xposedModule: XposedModule)
    fun setCustomCarrierName(carrierName: String) = afterAttachTry {
        val managerClass = classLoader.loadClass(
            "com.android.keyguard.CarrierTextManager"
        )
        val callbackInfoClass = classLoader.loadClass(
            $$"com.android.keyguard.CarrierTextManager$CarrierTextCallbackInfo"
        )
        val method = managerClass.getDeclaredMethod("postToCallback", callbackInfoClass)
        xposedModule.hook(method).intercept { chain ->
            val carrierTextCallbackInfo = chain.args[0]
            if (carrierTextCallbackInfo != null) {
                runCatching { carrierTextCallbackInfo.reflect["carrierText"] = carrierName }
                runCatching { carrierTextCallbackInfo.reflect["carrierTextShort"] = carrierName }
            }
            chain.proceed()
        }
    }

    @SuppressLint("SetTextI18n")
    context(xposedModule: XposedModule)
    fun addBatteryLevelText(
        hidePercentSign: Boolean,
        hideChargingIcon: Boolean,
    ) = afterAttachTry(ONE_UI_VERSION >= 70000) {
        val batteryMeterViewClass = classLoader.loadClass("com.android.systemui.battery.BatteryMeterView")
        val viewId = View.generateViewId()

        val scaleMethod = batteryMeterViewClass.getDeclaredMethod("scaleBatteryMeterViewsLegacy")
        xposedModule.hook(scaleMethod).intercept { chain ->
            val result = chain.proceed()
            try {
                val batteryMeterView = chain.thisObject as ViewGroup
                var textView = batteryMeterView.findViewById<TextView>(viewId)
                if (textView == null) {
                    textView = TextView(batteryMeterView.context).apply {
                        id = viewId
                        gravity = Gravity.CENTER
                    }
                    batteryMeterView.addView(
                        textView, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                val level = batteryMeterView.reflect["mLevel"] as? Int ?: 0
                val percent = if (hidePercentSign) "$level" else "$level%"
                val isCharging = batteryMeterView.reflect.call("isCharging") as? Boolean ?: false
                val suffix = if (isCharging && !hideChargingIcon) "\u26A1\uFE0E" else ""
                textView.text = "$percent$suffix"
                val textColor = batteryMeterView.reflect["mTextColor"] as? Int ?: 0
                textView.setTextColor(textColor)
            } catch (t: Throwable) {
                xlog(t)
            }
            result
        }

        batteryMeterViewClass.declaredMethods
            .filter { it.name == "updateColors" }
            .forEach { method ->
                xposedModule.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val view = chain.thisObject as ViewGroup
                        val textView = view.findViewById<TextView>(viewId)
                        val textColor = view.reflect["mTextColor"] as? Int ?: 0
                        textView?.setTextColor(textColor)
                    } catch (t: Throwable) {
                        xlog(t)
                    }
                    result
                }
            }
    }
}
