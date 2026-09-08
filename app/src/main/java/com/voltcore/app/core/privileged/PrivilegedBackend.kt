package com.voltcore.app.core.privileged

/**
 * How much authority VoltCore currently has, worst to best.
 *
 * Android gives ordinary apps no way to stop another app — killBackgroundProcesses has
 * been a no-op for other packages since Android 8. So every genuine freezing feature
 * depends on borrowing authority from somewhere, and the app has to be honest with the
 * user about which of these it actually has.
 */
enum class PrivilegeTier(val label: String, val rank: Int) {
    /** Nothing available; the app can only report, not act. */
    NONE("Not available", 0),

    /** Deep-link the user into Settings and let them tap Force stop themselves. */
    MANUAL("Manual", 1),

    /** Drive the Settings UI on the user's behalf. Slow and OEM-fragile. */
    ACCESSIBILITY("Accessibility", 2),

    /** Set once over ADB; can suspend packages via DevicePolicyManager. */
    DEVICE_OWNER("Device owner", 3),

    /** Shell-UID access brokered by Shizuku. No root, no system modification. */
    SHIZUKU("Shizuku", 4),

    /** Present only if the device already has it. Never requested or suggested. */
    ROOT("Root", 5),
}

/** What "freeze" should mean for a given app. */
enum class FreezeMethod(val label: String, val description: String) {
    /**
     * Stops the app now. It can come back the next time something wakes it, so this is
     * a one-shot cleanup rather than a lasting pause.
     */
    FORCE_STOP(
        "Force stop",
        "Kills the app right now. It can restart when something wakes it.",
    ),

    /**
     * Marks the app suspended: it cannot run or be launched until unsuspended, and the
     * launcher greys it out. This is the closest thing to a true pause.
     */
    SUSPEND(
        "Pause",
        "Stops the app and keeps it stopped until you resume it. Notifications stop too.",
    ),

    /**
     * Disables the package outright. Strongest, and the app disappears from the launcher.
     */
    DISABLE(
        "Disable",
        "Hides the app completely until you re-enable it. Strongest option.",
    ),
}

/** The result of trying to act on a package. */
sealed interface PrivilegeResult {
    data object Success : PrivilegeResult
    data class Failed(val reason: String) : PrivilegeResult
    /** The tier cannot do this at all; the caller should fall back or tell the user. */
    data class Unsupported(val reason: String) : PrivilegeResult
}

/** A way of exercising authority over other packages. */
interface PrivilegedBackend {

    val tier: PrivilegeTier

    /** Cheap, non-blocking guess at whether this backend could work right now. */
    suspend fun isAvailable(): Boolean

    /** Establishes whatever connection or permission the backend needs. */
    suspend fun connect(): Boolean

    suspend fun forceStop(packageName: String): PrivilegeResult

    suspend fun setSuspended(packageName: String, suspended: Boolean): PrivilegeResult

    suspend fun setEnabled(packageName: String, enabled: Boolean): PrivilegeResult

    /** Moves an app into a restrictive App Standby bucket. */
    suspend fun setStandbyBucket(packageName: String, bucket: String): PrivilegeResult

    /** Writes a Settings.Global value, e.g. to flip battery saver on. */
    suspend fun putGlobalSetting(key: String, value: String): PrivilegeResult

    /** True when this backend can make a freeze persist across app restarts. */
    val supportsPersistentFreeze: Boolean
        get() = false
}
