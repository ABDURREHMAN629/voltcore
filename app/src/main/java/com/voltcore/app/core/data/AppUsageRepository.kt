package com.voltcore.app.core.data

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.voltcore.app.core.privileged.LocalDumpsys
import com.voltcore.app.core.privileged.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row in the "what used the battery" list. */
data class AppPowerUse(
    /** Null for system rows such as Phone calls, which are not apps. */
    val packageName: String?,
    val uid: Int?,
    val label: String,
    /** Attributed drain in mAh. Null when only foreground time is known. */
    val mah: Double?,
    /** Time in the foreground over the window. Null when usage access is not granted. */
    val foregroundMs: Long?,
    val isSystem: Boolean,
    /**
     * The part of [mah] spent while the app was not on screen: background work, foreground
     * services such as music, and time spent cached. Null when the source cannot split it.
     */
    val offScreenMah: Double? = null,
) {
    /** True when this row is system overhead rather than an installed app. */
    val isSystemBucket: Boolean get() = packageName == null && uid == null

    /** Fraction of this app's drain that happened off screen, 0..1. */
    val offScreenShare: Double?
        get() {
            val total = mah ?: return null
            val off = offScreenMah ?: return null
            return if (total > 0.0) (off / total).coerceIn(0.0, 1.0) else null
        }
}

/** Where a battery-usage list came from, so the UI can be honest about it. */
enum class UsageSource {
    /**
     * Android's own per-app power estimate, read with `DUMP`. It is modelled from how
     * long each app kept each component busy, not metered — most phones, including ones
     * with no charge counter, have nothing to meter it with.
     */
    BATTERY_STATS,

    /** Screen time as a proxy. Honest, but it is not the same as battery drain. */
    USAGE_STATS,

    /** Nothing available. */
    NONE,
}

data class UsageReport(
    val source: UsageSource,
    val entries: List<AppPowerUse>,
    /** Sum of every row. Rows never overlap, so this is a real total, not a double count. */
    val totalMah: Double? = null,
    /**
     * Everything Android says was drawn from the battery over the same period, whether or
     * not it could pin it on an app. On phones that cannot attribute processor power this
     * is several times [totalMah], and the UI has to say so.
     */
    val drainMah: Double? = null,
) {
    /** Share of the period's drain the rows account for, 0..1. */
    val coverage: Double?
        get() {
            val drain = drainMah ?: return null
            val rows = totalMah ?: return null
            return if (drain > 0.0) (rows / drain).coerceIn(0.0, 1.0) else null
        }
}

/**
 * Works out which apps are costing battery.
 *
 * There are two very different answers available, and which one you get depends on what
 * access the user has granted:
 *
 *  1. **Android's own estimate.** Android keeps per-app power accounting, readable by anything
 *     holding `DUMP`. That is granted to VoltCore once over USB (see [LocalDumpsys]), or
 *     borrowed from Shizuku for people who already run it — mAh per app, system apps
 *     included, split into on-screen and off-screen use.
 *  2. **Screen time as a proxy.** Without either, UsageStats is the best available, and
 *     it measures something different: how long an app was in the foreground. A sync
 *     adapter that never shows a UI can drain the battery hard and score zero here.
 *
 * The report says which of the two it is so the UI never presents screen time as though
 * it were power use.
 */
class AppUsageRepository(
    private val context: Context,
    private val privilege: PrivilegeManager,
    private val dumpsys: LocalDumpsys,
) {

    private val pm: PackageManager get() = context.packageManager

    suspend fun report(sinceMs: Long, untilMs: Long = System.currentTimeMillis()): UsageReport =
        withContext(Dispatchers.IO) {
            batteryStatsReport()?.let { return@withContext it }
            usageStatsReport(sinceMs, untilMs)
        }

    /**
     * Reads `dumpsys batterystats --usage`.
     *
     * Not `--checkin`. That flag looks like the obvious machine-readable choice, but its own
     * help text says it "will write (and clear) the last old completed stats": when Android
     * is holding a snapshot of the previous battery period, `--checkin` returns *that*
     * period instead of the current one, then deletes it. An earlier version used it, and
     * so showed stale figures under a "current" label while wiping a file the system keeps
     * for its own usage reporting. `--usage` only reads, is a few tens of kilobytes rather
     * than the megabytes `-c` produces, and splits each app into on- and off-screen use.
     */
    private suspend fun batteryStatsReport(): UsageReport? {
        val dump = readUsageDump() ?: return null
        val entries = parseUsage(dump)
        if (entries.isEmpty()) return null
        return UsageReport(
            source = UsageSource.BATTERY_STATS,
            entries = entries.sortedByDescending { it.mah ?: 0.0 },
            totalMah = entries.mapNotNull { it.mah }.sum(),
            drainMah = parseDrain(dump),
        )
    }

    /**
     * VoltCore's own process first, using the one-time USB grant of `DUMP`; Shizuku only as a
     * fallback for people who already run it. Either way it is the same command and output.
     */
    private suspend fun readUsageDump(): String? {
        dumpsys.read(USAGE_ARGS)?.let { return it }
        if (!privilege.shizuku.isAvailable()) return null
        return privilege.shizuku.readCommand(USAGE_COMMAND)
    }

    /**
     * The period's total drain. "actual drain" comes from how far the level fell, so it is
     * real even on a phone with no charge counter; "computed drain" is the fallback.
     */
    private fun parseDrain(dump: String): Double? =
        (ACTUAL_DRAIN.find(dump) ?: COMPUTED_DRAIN.find(dump))
            ?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it > 0.0 }

    /**
     * Parses the human-readable `--usage` dump:
     *
     * ```
     *     Estimated power use (mAh):
     *       Capacity: 5000, Computed drain: 8510, actual drain: 8510
     *     Global
     *       phone: 210 apps: 0
     *       mobile_radio: 1300 apps: 1196
     *       (on battery, screen on)
     *         ...
     *     UID u0a177: 523 fg: 74.4 (1h 15m) bg: 367 (1d 21h) fgs: 76.3 (1h 25m) cached: 0.793
     * ```
     *
     * Each `UID` row is one app, already including its share of the radio, screen and so
     * on. The `Global` component rows therefore overlap the app rows, and listing both
     * would count the same energy twice. So from each component only the part *not*
     * attributed to any app — `total - apps` — is kept, as system overhead: phone calls,
     * the radio idling on the network.
     *
     * Even so, the rows need not add up to the period's drain. Processor power is
     * estimated from each app's time at each clock speed, and a kernel that does not
     * report that per app — no /proc/uid_time_in_state and no BPF equivalent — leaves
     * Android recording CPU *time* it cannot convert to mAh. Every other component is
     * also only as good as the device's power profile, which can be badly off — the Redmi
     * this was developed against rates its screen at 3–35 mA, a fraction of what a phone
     * screen draws. Together that left apps and overhead explaining about 28% of the
     * drain. [UsageReport.coverage] exposes the gap rather than hiding it.
     */
    private fun parseUsage(dump: String): List<AppPowerUse> {
        val out = mutableListOf<AppPowerUse>()
        var inGlobal = false

        for (raw in dump.lineSequence()) {
            val line = raw.trim()

            if (line == GLOBAL_HEADER) {
                inGlobal = true
                continue
            }
            if (inGlobal) {
                if (line.startsWith("(") || line.startsWith("UID ")) {
                    inGlobal = false
                } else {
                    COMPONENT_LINE.matchEntire(line)?.let { m ->
                        val label = COMPONENT_LABELS[m.groupValues[1]] ?: return@let
                        val total = m.groupValues[2].toDoubleOrNull() ?: return@let
                        val attributed = m.groupValues[3].toDoubleOrNull() ?: 0.0
                        val overhead = total - attributed
                        if (overhead >= MIN_MAH) out += overheadRow(label, overhead)
                    }
                    continue
                }
            }

            val m = UID_LINE.matchEntire(line) ?: continue
            val uid = parseUid(m.groupValues[1]) ?: continue
            val mah = m.groupValues[2].toDoubleOrNull() ?: continue
            if (mah < MIN_MAH) continue

            val states = STATE_PART.findAll(m.groupValues[3])
                .associate { it.groupValues[1] to (it.groupValues[2].toDoubleOrNull() ?: 0.0) }
            val offScreen = (states["bg"] ?: 0.0) + (states["fgs"] ?: 0.0) + (states["cached"] ?: 0.0)

            val pkg = packagesForUid(uid)
            out += AppPowerUse(
                packageName = pkg,
                uid = uid,
                label = pkg?.let { labelOf(it) } ?: systemUidLabel(uid),
                mah = mah,
                foregroundMs = null,
                isSystem = pkg == null || isSystemPackage(pkg),
                offScreenMah = if (states.isEmpty()) null else offScreen,
            )
        }
        return out
    }

    private fun overheadRow(label: String, mah: Double) = AppPowerUse(
        packageName = null,
        uid = null,
        label = label,
        mah = mah,
        foregroundMs = null,
        isSystem = true,
    )

    /** Handles raw uids (`1000`) and the user-scoped app form (`u0a177`). */
    private fun parseUid(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val app = USER_APP_UID.matchEntire(token) ?: return null
        val user = app.groupValues[1].toIntOrNull() ?: return null
        val appId = app.groupValues[2].toIntOrNull() ?: return null
        return user * PER_USER_RANGE + FIRST_APP_UID + appId
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

    private fun labelOf(packageName: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun isSystemPackage(packageName: String): Boolean = runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private companion object {
        const val USAGE_COMMAND = "dumpsys batterystats --usage"
        val USAGE_ARGS = listOf("batterystats", "--usage")
        const val GLOBAL_HEADER = "Global"
        const val MIN_MAH = 0.05
        const val FIRST_APP_UID = 10000
        const val PER_USER_RANGE = 100000

        /** `mobile_radio: 1300 apps: 1196 ...` */
        val COMPONENT_LINE = Regex("""([a-z_]+): ([\d.]+) apps: ([\d.]+).*""")

        /** `UID u0a177: 523 fg: 74.4 (...) bg: 367 (...) ...` */
        val UID_LINE = Regex("""UID (\S+): ([\d.]+)(.*)""")

        /** `fgs` is listed before `fg` so the longer tag wins the alternation. */
        val STATE_PART = Regex("""\b(fgs|fg|bg|cached): ([\d.]+)""")

        val USER_APP_UID = Regex("""u(\d+)a(\d+)""")

        val ACTUAL_DRAIN = Regex("""actual drain: ([\d.]+)""")
        val COMPUTED_DRAIN = Regex("""Computed drain: ([\d.]+)""")

        /** Names for the unattributed remainder of each component. */
        val COMPONENT_LABELS = mapOf(
            "screen" to "Screen",
            "cpu" to "CPU",
            "bluetooth" to "Bluetooth",
            "camera" to "Camera",
            "audio" to "Audio",
            "video" to "Video",
            "flashlight" to "Flashlight",
            "mobile_radio" to "Mobile network",
            "sensors" to "Sensors",
            "gnss" to "GPS",
            "wifi" to "Wi-Fi",
            "wakelock" to "Wake locks",
            "memory" to "Memory",
            "phone" to "Phone calls",
            "ambient_display" to "Always-on display",
            "idle" to "Idle",
        )
    }
}
