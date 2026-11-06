package io.github.soclear.oneuix.hook.fod

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import java.util.Collections
import java.util.WeakHashMap

object FodSystemUi {
    context(module: XposedModule)
    fun install(initial: FodConfig) = afterAttachTry(ONE_UI_VERSION >= 90000) {
        val runtime = FodRuntime(module, this, initial)
        val bridge = Bridge(module, classLoader, runtime)
        // Use SystemUI's native visibility API for the single hide/show option.
        runtime.safely("Native fingerprint icon visibility hook is unsupported") { bridge.installIconVisibility() }
        // Firmware can use more than one presentation path; resolve each independently.
        runtime.safely("Fingerprint indication hooks are unsupported") { bridge.installIndications() }
        runtime.safely("Fingerprint popup hook is unsupported") { bridge.installPopup() }
        runtime.safely("Fingerprint toast hook is unsupported") { bridge.installToast() }
        runtime.safely("Lockscreen shortcut color hooks are unsupported") { bridge.installShortcutClockColor() }
    }

    private class Bridge(
        private val module: XposedModule,
        private val loader: ClassLoader,
        private val runtime: FodRuntime,
    ) {
        private val lastFingerprintIndications = WeakHashMap<Any, Boolean>()
        private val popups = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        private val toastTypes = WeakHashMap<View, String>()
        private val hiddenToasts = WeakHashMap<View, Float>()
        private val shortcutViews = WeakHashMap<ImageView, Boolean>()
        private val originalShortcutTints = WeakHashMap<ImageView, ColorStateList?>()

        fun installIconVisibility() {
            val fingerprintManager = loader.loadClass("android.hardware.fingerprint.FingerprintManager")
            val hide = fingerprintManager.getMethod("semHideUdfpsIcon")
            val show = fingerprintManager.getMethod("semShowUdfpsIcon")
            require(hide.parameterCount == 0 && hide.returnType == Void.TYPE)
            require(show.parameterCount == 0 && show.returnType == Void.TYPE)

            // Samsung's normal show request also clears the native hidden state. When
            // hiding is enabled, replace it with the paired native hide request so the
            // sensor's ordinary authentication/listening lifecycle remains untouched.
            module.hook(show).intercept { chain ->
                if (!runtime.state.config.hideIcon) {
                    chain.proceed()
                } else {
                    val hidden = runCatching { hide.invoke(chain.thisObject); true }
                        .onFailure { runtime.report("Cannot request native FOD icon hide", it) }
                        .getOrDefault(false)
                    if (hidden) null else chain.proceed()
                }
            }
        }

        fun installIndications() {
            val prefix = "com.android.systemui.statusbar."
            val types = listOf("KeyguardSecIndicationController\$SecKeyguardCallback",
                "KeyguardIndicationController\$BaseKeyguardCallback")
            var count = 0
            types.forEach { name ->
                val type = runCatching { loader.loadClass(prefix + name) }.getOrNull() ?: return@forEach
                type.declaredMethods.filter { method ->
                    method.name in setOf("onBiometricHelp", "onBiometricError", "onBiometricAuthFailed") &&
                        method.parameterTypes.any { it.name == "android.hardware.biometrics.BiometricSourceType" }
                }.forEach { method ->
                    module.hook(method).intercept { chain ->
                        val fingerprint = chain.args.any { (it as? Enum<*>)?.name == "FINGERPRINT" }
                        runtime.safely("Track fingerprint indication") {
                            chain.thisObject?.reflect?.get("this\$0")?.let {
                                lastFingerprintIndications[it] = fingerprint
                            }
                        }
                        // These callbacks belong to text controllers, never the authentication monitor.
                        if (runtime.state.config.hideIcon && fingerprint) null else chain.proceed()
                    }
                    count++
                }
            }
            val controller = loader.loadClass(prefix + "KeyguardIndicationController")
            controller.declaredMethods.filter { method ->
                method.name in setOf("showBiometricMessage", "showErrorMessageNowOrLater") &&
                    method.parameterTypes.any { it.name == "android.hardware.biometrics.BiometricSourceType" }
            }.forEach { method ->
                module.hook(method).intercept { chain ->
                    val fingerprint = chain.args.any { (it as? Enum<*>)?.name == "FINGERPRINT" }
                    chain.thisObject?.let { lastFingerprintIndications[it] = fingerprint }
                    if (runtime.state.config.hideIcon && fingerprint) null else chain.proceed()
                }
                count++
            }
            check(count > 0)
            runtime.onChanged {
                if (runtime.state.config.hideIcon) lastFingerprintIndications.toMap().forEach { (owner, isFingerprint) ->
                    if (isFingerprint) runtime.safely("Clear existing fingerprint hint") {
                        if (owner.javaClass.name == prefix + "KeyguardSecIndicationController") {
                            val event = loader.loadClass(prefix + "IndicationEventType").enumConstants
                                .first { (it as Enum<*>).name == "BIOMETRICS_HELP" }
                            owner.reflect.call("removeIndication", event)
                        } else owner.reflect.call("hideBiometricMessage")
                    }
                }
            }
        }

        fun installPopup() {
            val name = "com.android.keyguard.biometrics.KeyguardFingerprintGuidePopup"
            val popup = loader.loadClass(name)
            val clear = popup.getDeclaredMethod("clearGuidePopup").apply { isAccessible = true }
            val callback = loader.loadClass(name + "\$keyguardUpdateMonitorCallback\$1")
            val help = callback.declaredMethods.single { it.name == "onBiometricHelp" }
            module.hook(help).intercept { chain ->
                if (runtime.state.config.hideIcon && chain.args.any { (it as? Enum<*>)?.name == "FINGERPRINT" }) {
                    null
                } else chain.proceed()
            }
            module.hook(popup.getDeclaredMethod("onFinishInflate")).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { popups.add(it) }
                result
            }
            runtime.onChanged {
                if (runtime.state.config.hideIcon) popups.toList().forEach { clear.invoke(it) }
            }
        }

        /**
         * Samsung uses KeyguardSecAffordanceView for the two bottom lock-screen shortcuts.
         * Its mIsTaskTypeShortcut flag distinguishes native task shortcuts (for example,
         * camera/flashlight) from app-provided shortcuts. Only the former are recoloured.
         */
        fun installShortcutClockColor() {
            val affordance = loader.loadClass("com.android.systemui.statusbar.KeyguardSecAffordanceView")
            val setImageDrawable = affordance.getDeclaredMethod("setImageDrawable", Drawable::class.java)
                .apply { isAccessible = true }
            module.hook(setImageDrawable).intercept { chain ->
                val result = chain.proceed()
                runtime.safely("Apply lockscreen clock color to shortcut") {
                    (chain.thisObject as? ImageView)?.let { view ->
                        shortcutViews[view] = true
                        applyShortcutClockColor(view)
                    }
                }
                result
            }

            // The lock-screen clock can change its text color without rebuilding the
            // affordance views. Watch TextView color updates, but only react when the
            // changed TextView belongs to a tracked clock view.
            val textView = loader.loadClass("android.widget.TextView")
            val colorStateList = ColorStateList::class.java
            val colorMethods = textView.declaredMethods.filter { method ->
                method.name == "setTextColor" && (
                    method.parameterTypes.contentEquals(arrayOf(Integer.TYPE)) ||
                    method.parameterTypes.contentEquals(arrayOf(colorStateList))
                )
            }
            check(colorMethods.isNotEmpty())
            colorMethods.forEach { method ->
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    runtime.safely("Refresh lockscreen shortcut clock color") {
                        val changed = chain.thisObject as? TextView ?: return@safely
                        if (shortcutViews.keys.any { shortcut ->
                                val root = fieldValue(shortcut, "mClockView") as? View
                                root != null && isDescendantOf(changed, root)
                            }) {
                            shortcutViews.keys.toList().forEach(::applyShortcutClockColor)
                        }
                    }
                    result
                }
            }

            runtime.onChanged {
                shortcutViews.keys.toList().forEach(::applyShortcutClockColor)
            }
        }

        private fun applyShortcutClockColor(view: ImageView) {
            val isNativeTaskShortcut = fieldValue(view, "mIsTaskTypeShortcut") as? Boolean ?: false
            if (!runtime.state.config.shortcutsFollowClockColor || !isNativeTaskShortcut) {
                if (originalShortcutTints.containsKey(view)) {
                    view.imageTintList = originalShortcutTints.remove(view)
                }
                return
            }

            val clockRoot = fieldValue(view, "mClockView") as? View ?: return
            val color = findClockTextColor(clockRoot) ?: return
            if (!originalShortcutTints.containsKey(view)) {
                originalShortcutTints[view] = view.imageTintList
            }
            view.imageTintList = ColorStateList.valueOf(color)
        }

        private fun fieldValue(owner: Any, name: String): Any? = runCatching {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        }.getOrNull()

        private fun isDescendantOf(view: View, root: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current === root) return true
                current = (current.parent as? View)
            }
            return false
        }

        private fun findClockTextColor(root: View): Int? {
            var best: TextView? = null
            var bestScore = Float.NEGATIVE_INFINITY

            fun visit(view: View) {
                if (view is TextView) {
                    val text = view.text?.toString().orEmpty()
                    val score = view.textSize + if (text.any(Char::isDigit)) 10000f else 0f
                    if (text.isNotBlank() && score > bestScore) {
                        best = view
                        bestScore = score
                    }
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) visit(view.getChildAt(index))
                }
            }

            visit(root)
            return best?.currentTextColor
        }

        fun installToast() {
            val toast = loader.loadClass("com.android.keyguard.biometrics.KeyguardBiometricToastView")
            FodCompatibility.toastUpdates(toast).forEach { update ->
                val typeIndex = update.parameterTypes.indexOfFirst { it.name == toast.name + "\$ToastType" }
                module.hook(update).intercept { chain ->
                    val result = chain.proceed()
                    runtime.safely("Update fingerprint toast visibility") {
                        val view = chain.thisObject as? View ?: return@safely
                        toastTypes[view] = (chain.args[typeIndex] as? Enum<*>)?.name.orEmpty()
                        applyToast(view)
                    }
                    result
                }
            }
            runtime.onChanged { toastTypes.keys.toList().forEach(::applyToast) }
        }

        private fun applyToast(view: View) {
            val hide = runtime.state.config.hideIcon && toastTypes[view] in setOf(
                "FingerprintAuthenticationFail", "FingerprintAuthenticationError", "FingerprintAuthenticationHelp")
            if (hide) {
                if (!hiddenToasts.containsKey(view)) hiddenToasts[view] = view.alpha
                // Samsung animates the inner toast root. Hide its outer view, retaining
                // all native animation completion, haptic, and lock-icon callbacks.
                view.alpha = 0f
            } else hiddenToasts.remove(view)?.let { view.alpha = it }
        }
    }
}
