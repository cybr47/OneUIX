package io.github.soclear.oneuix.hook.fod

import android.content.Context
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.enqueueMainIdleTask
import io.github.soclear.oneuix.hook.util.reflect
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object FodSystemUi {
    context(module: XposedModule)
    fun install(initial: FodConfig) = afterAttachTry(ONE_UI_VERSION >= 90000) {
        val runtime = FodRuntime(module, this, initial)
        val bridge = Bridge(module, this, classLoader, runtime)
        // Hook only the preloaded framework FingerprintManager API during Application.attach()
        // so early UDFPS show requests are intercepted without loading heavy SystemUI/Keyguard
        // classes before SystemUIApplication.onCreate() and KeyguardViewMediator start.
        runtime.safely("Native fingerprint icon visibility hook is unsupported") {
            bridge.installEarlyIconVisibility()
        }
        enqueueMainIdleTask {
            runtime.start()
        }
        enqueueMainIdleTask {
            bridge.installDeferredHooks()
        }
    }

    private class Bridge(
        private val module: XposedModule,
        private val context: Context,
        private val loader: ClassLoader,
        private val runtime: FodRuntime,
    ) {
        private val lastFingerprintIndications = WeakHashMap<Any, Boolean>()
        private val popups = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        private val toastTypes = WeakHashMap<View, String>()
        private val hiddenToasts = WeakHashMap<View, Float>()
        private var fingerprintManagerClass: Class<*>? = null
        private var hideMethod: Method? = null
        @Volatile
        private var deferredHooksInstalled = false

        fun installEarlyIconVisibility() {
            val fingerprintManager = loader.loadClass("android.hardware.fingerprint.FingerprintManager")
            val hide = fingerprintManager.getMethod("semHideUdfpsIcon")
            val show = fingerprintManager.getMethod("semShowUdfpsIcon")
            require(hide.parameterCount == 0 && hide.returnType == Void.TYPE)
            require(show.parameterCount == 0 && show.returnType == Void.TYPE)
            fingerprintManagerClass = fingerprintManager
            hideMethod = hide

            // Samsung's normal show request also clears the native hidden state. When
            // hiding is enabled, replace it with the paired native hide request so the
            // sensor's ordinary authentication/listening lifecycle remains untouched.
            module.hook(show).intercept { chain ->
                if (!runtime.state.config.hideIcon) {
                    chain.proceed()
                } else {
                    installDeferredHooks()
                    val hidden = runCatching { hide.invoke(chain.thisObject); true }
                        .onFailure { runtime.report("Cannot request native FOD icon hide", it) }
                        .getOrDefault(false)
                    if (hidden) null else chain.proceed()
                }
            }

            runtime.onChanged {
                if (runtime.state.config.hideIcon) {
                    installDeferredHooks()
                    context.getSystemService(fingerprintManager)?.let { fpm ->
                        runCatching { hide.invoke(fpm) }
                            .onFailure { runtime.report("Cannot request native FOD icon hide", it) }
                    }
                }
            }
        }

        fun installDeferredHooks() {
            if (deferredHooksInstalled) return
            synchronized(this) {
                if (deferredHooksInstalled) return
                deferredHooksInstalled = true
            }
            // Use SystemUI's native visibility API for the single hide/show option.
            runtime.safely("Native fingerprint icon visibility hook is unsupported") { installIconVisibility() }
            // Firmware can use more than one presentation path; resolve each independently.
            runtime.safely("Fingerprint indication hooks are unsupported") { installIndications() }
            runtime.safely("Fingerprint popup hook is unsupported") { installPopup() }
            runtime.safely("Fingerprint toast hook is unsupported") { installToast() }
        }

        fun installIconVisibility() {
            val fingerprintManager = fingerprintManagerClass
                ?: loader.loadClass("android.hardware.fingerprint.FingerprintManager").also {
                    fingerprintManagerClass = it
                }
            val hide = hideMethod ?: fingerprintManager.getMethod("semHideUdfpsIcon").also {
                require(it.parameterCount == 0 && it.returnType == Void.TYPE)
                hideMethod = it
            }
            val monitor = loader.loadClass("com.android.keyguard.KeyguardSecUpdateMonitorImpl")
            val startListening = monitor.getDeclaredMethod(
                "startListeningForFingerprint",
                Boolean::class.javaPrimitiveType,
            )
            val isRunning = generateSequence(monitor) { it.superclass }
                .firstNotNullOfOrNull { clazz ->
                    runCatching { clazz.getDeclaredMethod("isFingerprintDetectionRunning") }.getOrNull()
                } ?: monitor.getMethod("isFingerprintDetectionRunning")
            val fpmField = monitor.getDeclaredField("mFpm").apply { isAccessible = true }
            require(startListening.returnType == Void.TYPE)
            require(isRunning.parameterCount == 0 && isRunning.returnType == Boolean::class.javaPrimitiveType)
            require(fingerprintManager.isAssignableFrom(fpmField.type))

            // In KeyguardSecUpdateMonitorImpl.updateFingerprintListeningState(int),
            // mFpm.semShowUdfpsIcon() runs before startListeningForFingerprint(false).
            // When fingerprint listening was stopped (e.g. while the notification shade
            // or Quick Settings was expanded), starting a new authenticate/detect session
            // resets the native UDFPS icon visibility, so re-apply semHideUdfpsIcon()
            // once the session is active.
            module.hook(startListening).intercept { chain ->
                val result = chain.proceed()
                if (runtime.state.config.hideIcon) {
                    val owner = chain.thisObject
                    if (owner != null && isRunning.invoke(owner) == true) {
                        fpmField.get(owner)?.let { fpm ->
                            runCatching { hide.invoke(fpm) }
                                .onFailure { runtime.report("Cannot request native FOD icon hide", it) }
                        }
                    }
                }
                result
            }

            if (runtime.state.config.hideIcon) {
                context.getSystemService(fingerprintManager)?.let { fpm ->
                    runCatching { hide.invoke(fpm) }
                        .onFailure { runtime.report("Cannot request native FOD icon hide", it) }
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
                val outerField = runCatching {
                    type.getDeclaredField("this\$0").apply { isAccessible = true }
                }.getOrNull()
                type.declaredMethods.filter { method ->
                    method.name in setOf("onBiometricHelp", "onBiometricError", "onBiometricAuthFailed") &&
                        method.parameterTypes.any { it.name == "android.hardware.biometrics.BiometricSourceType" }
                }.forEach { method ->
                    module.hook(method).intercept { chain ->
                        val fingerprint = chain.args.any { (it as? Enum<*>)?.name == "FINGERPRINT" }
                        runtime.safely("Track fingerprint indication") {
                            val owner = chain.thisObject?.let { obj ->
                                outerField?.get(obj) ?: obj.reflect["this\$0"]
                            }
                            if (owner != null) {
                                lastFingerprintIndications[owner] = fingerprint
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
            val outerField = runCatching {
                callback.getDeclaredField("this\$0").apply { isAccessible = true }
            }.getOrNull()
            val help = callback.declaredMethods.single { it.name == "onBiometricHelp" }
            module.hook(help).intercept { chain ->
                runtime.safely("Track fingerprint guide popup") {
                    val owner = chain.thisObject?.let { obj ->
                        outerField?.get(obj) ?: obj.reflect["this\$0"]
                    }
                    if (owner != null) popups.add(owner)
                }
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
