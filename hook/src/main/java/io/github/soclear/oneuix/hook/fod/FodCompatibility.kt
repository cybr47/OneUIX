package io.github.soclear.oneuix.hook.fod

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve fingerprint toast callbacks without relying on optional inner-class metadata. */
internal object FodCompatibility {
    fun toastUpdates(toast: Class<*>): List<Method> {
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
