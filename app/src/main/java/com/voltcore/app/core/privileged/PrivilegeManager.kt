package com.voltcore.app.core.privileged

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the UI needs to explain what VoltCore can and cannot do right now. */
data class PrivilegeState(
    val tier: PrivilegeTier = PrivilegeTier.MANUAL,
    val shizukuInstalled: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuPermission: Boolean = false,
    val shizukuPermanentlyDenied: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    /** WRITE_SECURE_SETTINGS granted once over USB: Battery Saver works without Shizuku. */
    val secureSettings: Boolean = false,
) {
    /** True when freezing can be done silently, without hijacking the screen. */
    val canFreezeSilently: Boolean get() = tier.rank >= PrivilegeTier.SHIZUKU.rank

    /** True when a freeze survives the app trying to restart itself. */
    val canPause: Boolean get() = tier.rank >= PrivilegeTier.SHIZUKU.rank
}

/**
 * Chooses how VoltCore acts on other apps, and tells the UI the truth about it.
 *
 * The tiers are tried best-first. Device owner is deliberately not implemented: enrolling
 * as device owner requires a device with no accounts on it, which is not a reasonable
 * thing to ask of someone who already has their phone set up. Root is likewise not
 * offered — Shizuku reaches the same capabilities without touching the system image.
 */
class PrivilegeManager(private val context: Context) {

    val shizuku = ShizukuBackend(context)

    private val _state = MutableStateFlow(PrivilegeState())
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    /** Re-reads every signal. Cheap; safe to call whenever the UI resumes. */
    suspend fun refresh(): PrivilegeState {
        val installed = isShizukuInstalled()
        val running = shizuku.isServiceRunning()
        val permitted = running && shizuku.hasPermission()
        val accessibility = isAccessibilityEnabled()
        val secure = canWriteSecureSettings()

        val tier = when {
            permitted -> PrivilegeTier.SHIZUKU
            accessibility -> PrivilegeTier.ACCESSIBILITY
            else -> PrivilegeTier.MANUAL
        }

        val next = PrivilegeState(
            tier = tier,
            shizukuInstalled = installed,
            shizukuRunning = running,
            shizukuPermission = permitted,
            shizukuPermanentlyDenied = running && !permitted && shizuku.isPermanentlyDenied(),
            accessibilityEnabled = accessibility,
            secureSettings = secure,
        )
        _state.value = next
        return next
    }

    /** The backend to use, or null when only the manual path is left. */
    suspend fun activeBackend(): PrivilegedBackend? =
        if (shizuku.isAvailable()) shizuku else null

    // ------------------------------------------------------------------ actions

    /**
     * Applies [method] to a package using the best available tier.
     *
     * Accessibility can only force stop; asking it to pause or disable is reported as
     * unsupported rather than silently downgraded, so the UI never claims an app is
     * paused when it was merely killed.
     */
    suspend fun freeze(packageName: String, method: FreezeMethod): PrivilegeResult {
        val backend = activeBackend()
        if (backend != null) {
            return when (method) {
                FreezeMethod.FORCE_STOP -> backend.forceStop(packageName)
                FreezeMethod.SUSPEND -> backend.setSuspended(packageName, true)
                FreezeMethod.DISABLE -> backend.setEnabled(packageName, false)
            }
        }

        if (isAccessibilityEnabled()) {
            return if (method == FreezeMethod.FORCE_STOP) {
                ForceStopAccessibilityService.enqueue(listOf(packageName))
                PrivilegeResult.Success
            } else {
                PrivilegeResult.Unsupported(
                    "Only Force stop works without Shizuku. Set up Shizuku to pause apps.",
                )
            }
        }

        return PrivilegeResult.Unsupported("Set up Shizuku or the accessibility helper first.")
    }

    suspend fun unfreeze(packageName: String, method: FreezeMethod): PrivilegeResult {
        val backend = activeBackend()
            ?: return PrivilegeResult.Unsupported("Shizuku is required to undo this.")
        return when (method) {
            FreezeMethod.FORCE_STOP -> PrivilegeResult.Success // nothing to undo
            FreezeMethod.SUSPEND -> backend.setSuspended(packageName, false)
            FreezeMethod.DISABLE -> backend.setEnabled(packageName, true)
        }
    }

    /** Queues a batch. Returns the packages that could not be handled. */
    suspend fun freezeAll(
        packages: List<String>,
        method: FreezeMethod,
        onEach: (String, PrivilegeResult) -> Unit = { _, _ -> },
    ): List<String> {
        val failures = mutableListOf<String>()
        val backend = activeBackend()

        if (backend == null && isAccessibilityEnabled() && method == FreezeMethod.FORCE_STOP) {
            ForceStopAccessibilityService.enqueue(packages)
            return emptyList()
        }

        for (pkg in packages) {
            val result = freeze(pkg, method)
            onEach(pkg, result)
            if (result !is PrivilegeResult.Success) failures += pkg
        }
        return failures
    }

    /**
     * Writes a Settings.Global value — Shizuku if it is connected, otherwise directly when
     * WRITE_SECURE_SETTINGS has been granted over USB. That permission carries the
     * `development` flag, so unlike pausing or disabling apps it needs no helper app.
     */
    suspend fun putGlobalSetting(key: String, value: String): PrivilegeResult {
        activeBackend()?.let { return it.putGlobalSetting(key, value) }
        if (!canWriteSecureSettings()) {
            return PrivilegeResult.Unsupported("Needs the one-time USB permission.")
        }
        val written = runCatching {
            Settings.Global.putString(context.contentResolver, key, value)
        }.getOrDefault(false)
        return if (written) PrivilegeResult.Success else PrivilegeResult.Failed("Android refused.")
    }

    // ------------------------------------------------------------------ detection

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isAccessibilityEnabled(): Boolean = runCatching {
        val expected = "${context.packageName}/${ForceStopAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        splitter.any { it.equals(expected, ignoreCase = true) }
    }.getOrDefault(false)

    suspend fun requestShizukuPermission(): Boolean {
        val granted = shizuku.requestPermission()
        refresh()
        return granted
    }

    // ------------------------------------------------------------------ deep links

    /** Opens the Play listing (or the installed app) for Shizuku. */
    fun openShizuku() {
        val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_STORE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** The always-available fallback: show the user the page and let them press the button. */
    fun openAppInfo(packageName: String) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_STORE_URL =
            "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
    }
}
