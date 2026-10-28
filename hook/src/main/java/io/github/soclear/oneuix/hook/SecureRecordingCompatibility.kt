package io.github.soclear.oneuix.hook

/** Signature checks shared by the recorder and system-server hooks. */
internal object SecureRecordingCompatibility {
    fun isDisplayControlCreation(name: String, parameters: List<String>): Boolean {
        if (name != "createDisplay" && name != "createVirtualDisplay") return false
        return parameters == listOf("java.lang.String", "boolean") ||
                parameters == listOf("java.lang.String", "boolean", "java.lang.String", "float")
    }

    /**
     * Both VirtualDisplayAdapter and DisplayManagerService put flags immediately
     * between Surface and VirtualDisplayConfig. Never guess the first integer:
     * the earlier integer is the owner's UID, not a display flag.
     */
    fun virtualDisplayFlagsIndex(name: String, parameters: List<String>): Int? {
        if (name != "createVirtualDisplayLocked" || parameters.size !in 8..9) return null
        if (parameters.take(5) != listOf(
                "android.hardware.display.IVirtualDisplayCallback",
                "android.media.projection.IMediaProjection",
                "int", "java.lang.String", "java.lang.String"
            )) return null
        if (parameters.size == 9 && parameters[5] != "android.companion.virtual.IVirtualDevice") {
            return null
        }
        if (parameters.takeLast(3) != listOf(
                "android.view.Surface", "int", "android.hardware.display.VirtualDisplayConfig"
            )) return null
        return parameters.lastIndex - 1
    }

    fun isMediaProjectionCreation(name: String, parameters: List<String>): Boolean =
        name == "createVirtualDisplay" && parameters == listOf(
            "java.lang.String", "int", "int", "int", "int", "android.view.Surface",
            "android.hardware.display.VirtualDisplay\$Callback", "android.os.Handler"
        )
}
