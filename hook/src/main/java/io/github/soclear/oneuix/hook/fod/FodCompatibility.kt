package io.github.soclear.oneuix.hook.fod

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Contracts that do not depend on Samsung's sensor-info implementation or metadata. */
internal object FodCompatibility {
    data class IconSize(val width: Int, val height: Int)

    fun iconSize(layoutWidth: Int, layoutHeight: Int, viewWidth: Int, viewHeight: Int): IconSize? {
        // Prefer explicit native layout dimensions: measured bounds may still
        // describe the previous configuration until the next layout pass.
        fun dimension(layout: Int, actual: Int): Int? = when {
            layout in 1..4096 -> layout
            layout <= 0 && actual in 1..4096 -> actual
            else -> null
        }
        val width = dimension(layoutWidth, viewWidth) ?: return null
        val height = dimension(layoutHeight, viewHeight) ?: return null
        return IconSize(width, height)
    }

    fun toastUpdates(toast: Class<*>): List<Method> {
        // Class.simpleName depends on optional InnerClass metadata. Samsung's
        // optimized classes can retain '$ToastType' without that metadata.
        val typeName = toast.name + "\$ToastType"
        val methods = toast.declaredMethods.filter { method ->
            method.name == "update" && method.returnType == Void.TYPE &&
                !Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.any { it.name == typeName && it.isEnum }
        }
        check(methods.isNotEmpty()) {
            "No fingerprint toast update in ${toast.name}: " +
                toast.declaredMethods.filter { it.name == "update" }.joinToString { it.toGenericString() }
        }
        return methods
    }
}
