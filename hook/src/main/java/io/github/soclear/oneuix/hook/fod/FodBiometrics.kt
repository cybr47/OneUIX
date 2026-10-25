package io.github.soclear.oneuix.hook.fod

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Samsung renders the FOD in Biometrics, separately from SystemUI's text. */
object FodBiometrics {
    private const val GLASS_BASE_FACTOR = 1.5f
    private const val GLASS_ALPHA = 0xB3

    context(module: XposedModule)
    fun install(initial: FodConfig) = afterAttachTry(ONE_UI_VERSION >= 90000) {
        val runtime = FodRuntime(module, this, initial)
        runtime.safely("Biometric icon hook is unsupported on this firmware") {
            Bridge(module, classLoader, runtime).install()
        }
    }

    private class Bridge(
        private val module: XposedModule,
        loader: ClassLoader,
        private val runtime: FodRuntime,
    ) {
        private val keyguard = loader.loadClass(
            "com.samsung.android.biometrics.app.setting.fingerprint.UdfpsKeyguardSensorWindow")
        private val sensor = loader.loadClass(
            "com.samsung.android.biometrics.app.setting.fingerprint.UdfpsSensorWindow")
        private val windows = WeakHashMap<Any, WindowState>()

        fun install() {
            // Resolve the full required contract before installing any hooks.
            require(sensor.isAssignableFrom(keyguard))
            listOf("mAnimationView", "mFpIconContainer", "mFingerprintLayout").forEach {
                sensor.reflect.findField(it)
            }
            require(ImageView::class.java.isAssignableFrom(sensor.reflect.findField("mAnimationView").type))
            require(RelativeLayout::class.java.isAssignableFrom(sensor.reflect.findField("mFpIconContainer").type))
            keyguard.reflect.findField("mHelpMessageOnAodView")
            val initialize = sensor.declaredMethods.single { it.name == "initLayout" && it.parameterCount == 1 }
            val remove = keyguard.reflect.findMethodExact("removeView")
            val updates = hierarchy(keyguard).flatMap { it.declaredMethods.toList() }.filter {
                it.name in setOf("initSensorLayout", "setSensorIcon", "setAnimationTypeIfNeeded",
                    "showSensorIcon", "showSensorIconWithAnimation", "showHelpMessageOnAod",
                    "updateSensorViewLocation", "onConfigurationChanged", "onRotationInfoChanged")
            }.distinct()
            require(updates.any { it.name == "showSensorIcon" })

            // Samsung reuses the same base layout for keyguard, apps, and enrollment.
            // Restore our changes BEFORE any client reinitializes that shared layout.
            module.hook(initialize).intercept { chain ->
                val root = chain.args.firstOrNull() as? ViewGroup
                runtime.safely("Restore shared fingerprint layout") {
                    windows.entries.filter { it.value.root.get() === root }.toList().forEach { entry ->
                        entry.value.restore()
                        windows.remove(entry.key)
                    }
                }
                val result = chain.proceed()
                if (keyguard.isInstance(chain.thisObject)) runtime.safely("Initialize keyguard FOD") {
                    apply(requireNotNull(chain.thisObject))
                }
                result
            }
            updates.forEach { method ->
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (keyguard.isInstance(chain.thisObject)) runtime.safely("Update keyguard FOD") {
                        apply(requireNotNull(chain.thisObject))
                    }
                    result
                }
            }
            module.hook(remove).intercept { chain ->
                // Restore before native removal can hand the base view to another client.
                runtime.safely("Release keyguard FOD") { windows.remove(chain.thisObject)?.restore() }
                chain.proceed()
            }
            runtime.onChanged {
                windows.keys.toList().forEach { window ->
                    runtime.safely("Apply FOD setting", { apply(window) })
                }
            }
        }

        private fun apply(window: Any) {
            val original = window.reflect["mAnimationView"] as? ImageView ?: return
            val container = window.reflect["mFpIconContainer"] as? RelativeLayout ?: return
            val root = window.reflect["mFingerprintLayout"] as? ViewGroup ?: return
            val state = windows.getOrPut(window) {
                val target = WeakReference(window)
                WindowState(root, container, original) {
                    target.get()?.let { current ->
                        runtime.safely("Resize keyguard FOD") { apply(current) }
                    }
                }
            }
            if (!runtime.state.config.hideIcon) {
                state.restore()
                return
            }
            // FingerprintSensorInfo on newer firmware has no getImageSize(). The
            // native view already has Samsung's size, including density/rotation.
            // Hide independently of sizing so a pending layout cannot reveal it.
            state.hide(original)
            (window.reflect["mHelpMessageOnAodView"] as? View)?.let(state::hide)
            // Preserve the effect's completion callbacks and sensor feedback lifecycle.
            runCatching { window.reflect["mVisualEffectView"] as? View }.getOrNull()?.let(state::hide)
            val bitmap = runtime.state.bitmap
            val glass = runtime.state.config.glassBackground
            if (bitmap == null && !glass) {
                state.removeReplacement()
                state.removeGlass()
                return
            }
            state.observeLayout()
            val size = FodCompatibility.iconSize(original.layoutParams?.width ?: 0,
                original.layoutParams?.height ?: 0, original.width, original.height)
            if (size == null) {
                state.removeReplacement()
                state.removeGlass()
                return
            }
            if (glass) applyGlass(state, container, original, size) else state.removeGlass()
            if (bitmap == null) {
                state.removeReplacement()
                return
            }
            val replacement = state.replacement.get() ?: ImageView(original.context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.FIT_CENTER
                container.addView(this, RelativeLayout.LayoutParams(size.width, size.height).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                })
                state.replacement = WeakReference(this)
            }
            state.allowOverflow()
            if (replacement.layoutParams.width != size.width || replacement.layoutParams.height != size.height) {
                replacement.layoutParams = RelativeLayout.LayoutParams(size.width, size.height).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
            }
            replacement.setImageBitmap(bitmap)
            replacement.scaleX = runtime.state.config.scale
            replacement.scaleY = runtime.state.config.scale
            // Container visibility/position/rotation remain entirely Samsung-controlled.
        }

        // Theme-following circle behind the icon. The shortcut tiles fill a CircleShape
        // with Material surfaceContainerHigh; the framework exposes the same role as
        // android.R.color.system_surface_container_high_{light,dark}.
        private fun applyGlass(state: WindowState, container: RelativeLayout, original: ImageView,
                               size: FodCompatibility.IconSize) {
            val diameter = (maxOf(size.width, size.height) * GLASS_BASE_FACTOR).toInt().coerceAtLeast(1)
            val view = state.glass.get() ?: View(original.context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // Behind the native and replacement icon views.
                container.addView(this, 0, RelativeLayout.LayoutParams(diameter, diameter).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                })
                state.glass = WeakReference(this)
            }
            state.allowOverflow()
            if (view.layoutParams.width != diameter || view.layoutParams.height != diameter) {
                view.layoutParams = RelativeLayout.LayoutParams(diameter, diameter).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
            }
            val night = (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val color = view.context.getColor(
                if (night) android.R.color.system_surface_container_high_dark
                else android.R.color.system_surface_container_high_light)
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((color and 0x00ffffff) or (GLASS_ALPHA shl 24))
            }
            view.scaleX = runtime.state.config.glassScale
            view.scaleY = runtime.state.config.glassScale
        }

        private fun hierarchy(type: Class<*>): List<Class<*>> =
            generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.toList()
    }

    private class WindowState(root: ViewGroup, container: ViewGroup, original: View, onLayout: () -> Unit) {
        val root = WeakReference(root)
        private val container = WeakReference(container)
        private val originalView = WeakReference(original)
        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onLayout() }
        private var observingLayout = false
        var replacement = WeakReference<ImageView>(null)
        var glass = WeakReference<View>(null)
        private val hidden = WeakHashMap<View, Float>()
        private val rootClipChildren = root.clipChildren
        private val rootClipPadding = root.clipToPadding
        private val containerClipChildren = container.clipChildren
        private val containerClipPadding = container.clipToPadding
        private var changedClipping = false

        init { require(original.parent === container) }

        fun hide(view: View) {
            if (!hidden.containsKey(view)) hidden[view] = view.alpha
            view.alpha = 0f
        }

        fun observeLayout() {
            if (!observingLayout) {
                originalView.get()?.addOnLayoutChangeListener(layoutListener)
                observingLayout = true
            }
        }

        fun allowOverflow() {
            root.get()?.apply { clipChildren = false; clipToPadding = false }
            container.get()?.apply { clipChildren = false; clipToPadding = false }
            changedClipping = true
        }

        fun removeReplacement() {
            replacement.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
            replacement.clear()
            releaseClipping()
        }

        fun removeGlass() {
            glass.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
            glass.clear()
            releaseClipping()
        }

        private fun releaseClipping() {
            if (glass.get() == null && replacement.get() == null && changedClipping) {
                root.get()?.apply { clipChildren = rootClipChildren; clipToPadding = rootClipPadding }
                container.get()?.apply { clipChildren = containerClipChildren; clipToPadding = containerClipPadding }
                changedClipping = false
            }
        }

        fun restore() {
            originalView.get()?.removeOnLayoutChangeListener(layoutListener)
            observingLayout = false
            removeReplacement()
            removeGlass()
            hidden.forEach { (view, alpha) -> view.alpha = alpha }
            hidden.clear()
        }
    }
}
