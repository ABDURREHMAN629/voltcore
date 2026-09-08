package com.voltcore.app.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed app as the freezer sees it. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val isSuspended: Boolean,
    val isLaunchable: Boolean,
    /** Milliseconds in the foreground over the lookback window, when we may know. */
    val foregroundMs: Long? = null,
    val lastUsedMs: Long? = null,
) {
    /** Apps that stopping would break the phone, or that cannot be stopped at all. */
    val isProtected: Boolean
        get() = packageName in PROTECTED_PACKAGES ||
            PROTECTED_PREFIXES.any { packageName.startsWith(it) }

    companion object {
        /**
         * Never offer to freeze these. Killing the launcher, the IME or the phone dialer
         * makes the device unusable in ways a user will not connect back to this app.
         */
        val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.voltcore.app",
        )
        val PROTECTED_PREFIXES = listOf(
            "com.android.providers.",
            "com.android.inputmethod",
            "com.google.android.inputmethod",
            "com.android.permission",
            "com.google.android.permission",
        )
    }
}

class InstalledAppsRepository(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /**
     * Lists installed apps.
     *
     * Usage figures are folded in only when the user has granted usage access; without it
     * the fields stay null and the UI hides the "most used" ordering rather than sorting
     * everything by a silent zero.
     */
    suspend fun load(includeSystem: Boolean = false): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val usage = runCatching { usageByPackage() }.getOrNull()

            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { includeSystem || !it.isSystemApp() || it.isUpdatedSystemApp() }
                .map { info ->
                    val stats = usage?.get(info.packageName)
                    InstalledApp(
                        packageName = info.packageName,
                        label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName),
                        isSystem = info.isSystemApp(),
                        isEnabled = info.enabled,
                        isSuspended = (info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0,
                        isLaunchable = pm.getLaunchIntentForPackage(info.packageName) != null,
                        foregroundMs = stats?.first,
                        lastUsedMs = stats?.second,
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        }

    /** Re-reads just the suspended/enabled flags, to refresh the list after a freeze. */
    suspend fun stateOf(packageName: String): Pair<Boolean, Boolean>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                info.enabled to ((info.flags and ApplicationInfo.FLAG_SUSPENDED) != 0)
            }.getOrNull()
        }

    fun hasUsageAccess(): Boolean = runCatching {
        val end = System.currentTimeMillis()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            end - USAGE_WINDOW_MS,
            end,
        )
        !stats.isNullOrEmpty()
    }.getOrDefault(false)

    /** package -> (foreground millis, last used millis) */
    private fun usageByPackage(): Map<String, Pair<Long, Long>>? {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val stats = usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            end - USAGE_WINDOW_MS,
            end,
        ) ?: return null
        if (stats.isEmpty()) return null

        return stats.groupBy { it.packageName }
            .mapValues { (_, entries) ->
                entries.sumOf { it.totalTimeInForeground } to
                    entries.maxOf { it.lastTimeUsed }
            }
    }

    private fun ApplicationInfo.isSystemApp() =
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun ApplicationInfo.isUpdatedSystemApp() =
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    private companion object {
        const val USAGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    }
}
