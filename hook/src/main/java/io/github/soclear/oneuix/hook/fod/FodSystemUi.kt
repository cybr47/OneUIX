package io.github.soclear.oneuix.hook.fod

import android.view.View
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
        val runtime = FodRuntime(module, this, initial, loadIcon = false)
        val bridge = Bridge(module, classLoader, runtime)
        // Firmware can use more than one presentation path; resolve each independently.
        runtime.safely("Fingerprint indication hooks are unsupported") { bridge.installIndications() }
        runtime.safely("Fingerprint popup hook is unsupported") { bridge.installPopup() }
        runtime.safely("Fingerprint toast hook is unsupported") { bridge.installToast() }
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
            runtime.report("Installed fingerprint-only indication hooks")
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

        fun installToast() {
            val toast = loader.loadClass("com.android.keyguard.biometrics.KeyguardBiometricToastView")
            val update = toast.declaredMethods.single { it.name == "update" &&
                it.parameterTypes.firstOrNull()?.simpleName == "ToastType" }
            module.hook(update).intercept { chain ->
                val result = chain.proceed()
                runtime.safely("Update fingerprint toast visibility") {
                    val view = chain.thisObject as? View ?: return@safely
                    toastTypes[view] = (chain.args.firstOrNull() as? Enum<*>)?.name.orEmpty()
                    applyToast(view)
                }
                result
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
