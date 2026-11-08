package io.github.soclear.oneuix.hook.systemui

import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.hook.util.afterAttachTry

/**
 * Hides the Wi-Fi generation digit (5, 6, 6E, 7) next to the Wi-Fi icon.
 *
 * The icon set is chosen from the connected network's `wifiNetworkType`; every type other than NONE
 * selects an icon with a digit. The type of an active network is reset to NONE when the model is
 * created, so Samsung's own code then picks the plain Wi-Fi icon with all its usual behaviour
 * (signal levels, activity arrows, exclamation mark).
 */
internal object WifiGeneration {
    context(module: XposedModule)
    fun hideDigit() = afterAttachTry {
        val prefix = "com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel"
        val active = classLoader.loadClass("$prefix\$Active")
        val typeClass = classLoader.loadClass("$prefix\$WifiNetworkType")
        val typeField = active.getDeclaredField("wifiNetworkType").apply { isAccessible = true }
        check(typeField.type == typeClass)
        @Suppress("UNCHECKED_CAST")
        val none = java.lang.Enum.valueOf(typeClass as Class<out Enum<*>>, "NONE")
        // Both constructors: the full one and Kotlin's default-argument bridge.
        active.declaredConstructors.forEach { constructor ->
            module.hook(constructor).intercept { chain ->
                val result = chain.proceed()
                val model = chain.thisObject
                if (model != null && typeField.get(model) !== none) typeField.set(model, none)
                result
            }
        }
    }
}
