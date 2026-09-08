package com.voltcore.app.core.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.voltcore.app.core.privileged.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row in the "what used the battery" list. */
data class AppPowerUse(
    /** Null for system buckets such as Screen or Cell radio, which are not apps. */
    val packageName: String?,
    val uid: Int?,
    val label: String,
    /** Real attributed drain in mAh. Null when only foreground time is known. */
    val mah: Double?,
    /** Time in the foreground over the window. Null when usage access is not granted. */
    val foregroundMs: Long?,
    val isSystem: Boolean,
) {
    /** True when this row is a hardware bucket rather than an installed app. */
    val isSystemBucket: Boolean get() = packageName == null && uid == null
}

/** Where a battery-usage list came from, so the UI can be honest about it. */
enum class UsageSource {
    /** The system's own accounting, read through Shizuku. Real mAh per app. */
    BATTERY_STATS,

    /** Screen time as a proxy. Honest, but it is not the same as battery drain. */
    USAGE_STATS,

    /** Nothing available. */
    NONE,
}

data class UsageReport(
    val source: UsageSource,
    val entries: List<AppPowerUse>,
    /** Total computed drain in mAh, when the system reported one. */
    val totalMah: Double? = null,
)

/**
 * Works out which apps are actually costing battery.
 *
 * There are two very different answers available, and which one you get depends on what
 * access the user has granted:
 *
 *  1. **Real attribution.** Android keeps per-UID power accounting in `batterystats`, but
 *     reading it needs the BATTERY_STATS permission, which is signature-level and can
 *     never be granted to a normal app. Shell *can* read it, so with Shizuku VoltCore
 *     dumps and parses it — giving genuine mAh per app, system apps included.
 *  2. **Screen time as a proxy.** Without Shizuku, UsageStats is the best available, and
 *     it measures something different: how long an app was in the foreground. A sync
 *     adapter that never shows a UI can drain the battery hard and score zero here.
 *
 * The report says which of the two it is so the UI never presents screen time as though
 * it were power measurement.
 */
class AppUsageRepository(
    private val context: Context,
    private val privilege: PrivilegeManager,
) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun report(sinceMs: Long, untilMs: Long = System.currentTimeMillis()): UsageReport =
        withContext(Dispatchers.IO) {
            batteryStatsReport()?.let { return@withContext it }
            usageStatsReport(sinceMs, untilMs)
        }

    fun hasUsageAccess(): Boolean = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 60 * 60 * 1000, end)
        events != null && events.hasNextEvent()
    }.getOrDefault(false)

    // ------------------------------------------------------------- real attribution

    private suspend fun batteryStatsReport(): UsageReport? {
        if (!privilege.shizuku.isAvailable()) return null
        val dump = privilege.shizuku.readCommand("dumpsys batterystats --checkin") ?: return null
        val entries = parseCheckin(dump)
        if (entries.isEmpty()) return null
        return UsageReport(
            source = UsageSource.BATTERY_STATS,
            entries = entries.sortedByDescending { it.mah ?: 0.0 },
            totalMah = entries.mapNotNull { it.mah }.sum(),
        )
    }

    /** Full-charge capacity in mAh, per the system's own accounting. Needs Shizuku. */
    suspend fun batteryCapacityMah(): Float? {
        if (!privilege.shizuku.isAvailable()) return null
        val line = privilege.shizuku
            .readCommand("dumpsys batterystats | grep -m1 'Capacity:'")
            ?: return null
        // "    Capacity: 5000, Computed drain: 532, actual drain: 532"
        val after = line.substringAfter("Capacity:", "").trim()
        val digits = after.takeWhile { it.isDigit() }
        return digits.toFloatOrNull()?.takeIf { it in 500f..15_000f }
    }

    /**
     * Parses the machine-readable `dumpsys batterystats --checkin` output.
     *
     * The human-readable dump used to carry `Uid u0a213: 42.7` lines, and older guides
     * still say so, but on current Android its power section is component-only — screen,
     * cell, wifi and so on — with no per-app rows at all. The checkin format still has
     * them, and being CSV it is far less fragile than scraping indented prose:
     *
     * ```
     * 9,0,l,pwi,scrn,9.91,1,0,0        <- a hardware bucket
     * 9,10202,l,pwi,uid,0.375,0,...    <- uid 10202 drew 0.375 mAh
     * ```
     *
     * Field 1 is the uid, field 3 the section tag, field 4 the label and field 5 the
     * milliamp-hours. A label of `uid` means the row belongs to the uid in field 1;
     * anything else names a hardware component.
     */
    private fun parseCheckin(dump: String): List<AppPowerUse> {
        val out = mutableListOf<AppPowerUse>()

        for (line in dump.lineSequence()) {
            val f = line.split(',')
            if (f.size < 6) continue
            if (f[3] != PWI) continue

            val mah = f[5].toDoubleOrNull() ?: continue
            if (mah <= 0.0) continue

            if (f[4] == UID_LABEL) {
                val uid = f[1].toIntOrNull() ?: continue
                val pkg = packagesForUid(uid)
                out += AppPowerUse(
                    packageName = pkg,
                    uid = uid,
                    label = pkg?.let { labelOf(it) } ?: systemUidLabel(uid),
                    mah = mah,
                    foregroundMs = null,
                    isSystem = pkg == null || isSystemPackage(pkg),
                )
            } else {
                val label = COMPONENT_LABELS[f[4]] ?: continue
                out += AppPowerUse(
                    packageName = null,
                    uid = null,
                    label = label,
                    mah = mah,
                    foregroundMs = null,
                    isSystem = true,
                )
            }
        }
        return out
    }

    private fun packagesForUid(uid: Int): String? = runCatching {
        pm.getPackagesForUid(uid)?.firstOrNull()
    }.getOrNull()

    private fun systemUidLabel(uid: Int): String = when (uid) {
        0 -> "Android (root)"
        1000 -> "Android system"
        1001 -> "Telephony"
        1002 -> "Bluetooth"
        1010 -> "Wi-Fi"
        1013 -> "Media server"
        1027 -> "NFC"
        1041 -> "Audio server"
        else -> "UID $uid"
    }

    // ------------------------------------------------------------- screen-time proxy

    private fun usageStatsReport(sinceMs: Long, untilMs: Long): UsageReport {
        val usm = context.getSystemService(UsageStatsManager::class.java)
            ?: return UsageReport(UsageSource.NONE, emptyList())

        val foreground = runCatching { foregroundMsByPackage(usm, sinceMs, untilMs) }
            .getOrNull()
            ?: return UsageReport(UsageSource.NONE, emptyList())

        if (foreground.isEmpty()) return UsageReport(UsageSource.NONE, emptyList())

        val entries = foreground
            .filter { it.value > 1000L }
            .map { (pkg, ms) ->
                AppPowerUse(
                    packageName = pkg,
                    uid = null,
                    label = labelOf(pkg),
                    mah = null,
                    foregroundMs = ms,
                    isSystem = isSystemPackage(pkg),
                )
            }
            .sortedByDescending { it.foregroundMs ?: 0L }

        return UsageReport(UsageSource.USAGE_STATS, entries)
    }

    /**
     * Foreground milliseconds per package inside an arbitrary window.
     *
     * queryUsageStats buckets by day and cannot answer "during this charging session", so
     * this walks the raw event stream and pairs resume/pause events instead.
     */
    private fun foregroundMsByPackage(
        usm: UsageStatsManager,
        startMs: Long,
        endMs: Long,
    ): Map<String, Long> {
        val events = usm.queryEvents(startMs, endMs) ?: return emptyMap()
        val totals = mutableMapOf<String, Long>()
        val resumedAt = mutableMapOf<String, Long>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    resumedAt[pkg] = event.timeStamp

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    val from = resumedAt.remove(pkg) ?: continue
                    val delta = event.timeStamp - from
                    if (delta > 0) totals[pkg] = (totals[pkg] ?: 0L) + delta
                }
            }
        }

        // An app still in the foreground when the window ends has no pause event.
        for ((pkg, from) in resumedAt) {
            val delta = endMs - from
            if (delta > 0) totals[pkg] = (totals[pkg] ?: 0L) + delta
        }
        return totals
    }

    // ------------------------------------------------------------------ helpers

    private fun labelOf(packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun isSystemPackage(packageName: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private companion object {
        const val PWI = "pwi"
        const val UID_LABEL = "uid"

        /**
         * Checkin component tags. Rows tagged `???` are reserved or unnamed in the
         * platform's own table and are dropped rather than shown as a mystery.
         */
        val COMPONENT_LABELS = mapOf(
            "scrn" to "Screen",
            "cpu" to "CPU",
            "blue" to "Bluetooth",
            "camera" to "Camera",
            "audio" to "Audio",
            "video" to "Video",
            "flashlight" to "Flashlight",
            "cell" to "Mobile radio",
            "sensors" to "Sensors",
            "gnss" to "GPS",
            "wifi" to "Wi-Fi",
            "memory" to "Memory",
            "phone" to "Phone calls",
            "ambi" to "Ambient display",
            "idle" to "Idle",
        )
    }
}
