package com.voltcore.app.core.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Time one app spent on screen inside a window. */
data class AppScreenTime(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
)

/**
 * Screen-on time and per-app screen time, from Android's usage events.
 *
 * Needs only Usage access — a switch in the phone's own Settings, not another app and not a
 * USB command. Android keeps these events for roughly a week, so this covers recent days;
 * older days show battery use without the screen breakdown.
 */
class ScreenTimeRepository(private val context: Context) {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * Asks AppOps directly, rather than inferring access from whether queries come back empty.
     *
     * Two routes grant this. The Settings switch sets the app-op to ALLOWED. A USB
     * `pm grant ... PACKAGE_USAGE_STATS` instead grants the permission and leaves the app-op at
     * DEFAULT, which Android resolves by checking that permission — so DEFAULT has to be
     * resolved the same way here, or a working grant would read as missing.
     */
    fun hasUsageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkSelfPermission(USAGE_STATS_PERMISSION) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }.getOrDefault(false)

    /**
     * Screen-on milliseconds for each of the 24 hours of [date], or null when usage access is
     * missing or the platform does not report screen events (before Android 9).
     */
    suspend fun screenOnByHour(date: LocalDate): LongArray? = withContext(Dispatchers.IO) {
        if (!hasUsageAccess() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withContext null
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@withContext null
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // Start a little early: if the screen was already on at midnight, the "on" event is
        // in the previous day.
        val events = usm.queryEvents(start - LOOKBACK_MS, end) ?: return@withContext null

        val out = LongArray(24)
        var onSince: Long? = null
        val event = UsageEvents.Event()
        fun credit(from: Long, to: Long) {
            var s = maxOf(from, start)
            val e = minOf(to, end)
            while (s < e) {
                val hour = ((s - start) / HOUR_MS).toInt().coerceIn(0, 23)
                val hourEnd = start + (hour + 1) * HOUR_MS
                val stop = minOf(hourEnd, e)
                out[hour] += stop - s
                s = stop
            }
        }
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (onSince == null) onSince = event.timeStamp
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    onSince?.let { credit(it, event.timeStamp) }
                    onSince = null
                }
            }
        }
        // Still on at the end of the window (or right now, for today).
        onSince?.let { credit(it, minOf(end, System.currentTimeMillis())) }
        out
    }

    /** Apps on screen between [startMs] and [endMs], most time first. */
    suspend fun appsBetween(startMs: Long, endMs: Long, limit: Int = 6): List<AppScreenTime>? =
        withContext(Dispatchers.IO) {
            if (!hasUsageAccess()) return@withContext null
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@withContext null
            foregroundMsByPackage(usm, startMs, endMs)
                .filter { it.value >= MIN_APP_MS && it.key != context.packageName }
                .entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { (pkg, ms) -> AppScreenTime(pkg, labelOf(pkg), ms) }
        }

    private fun labelOf(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val USAGE_STATS_PERMISSION = "android.permission.PACKAGE_USAGE_STATS"
        const val HOUR_MS = 60L * 60 * 1000
        const val LOOKBACK_MS = 6L * HOUR_MS
        const val MIN_APP_MS = 30_000L
    }
}

/**
 * Foreground milliseconds per package inside an arbitrary window.
 *
 * `queryUsageStats` buckets by whole days and cannot answer "between 19:00 and 20:00", so this
 * walks the raw event stream and pairs resume and pause events instead. An app resumed before
 * the window opened is counted from the window's start.
 */
internal fun foregroundMsByPackage(
    usm: UsageStatsManager,
    startMs: Long,
    endMs: Long,
): Map<String, Long> {
    val events = usm.queryEvents(startMs - FOREGROUND_LOOKBACK_MS, endMs) ?: return emptyMap()
    val totals = mutableMapOf<String, Long>()
    val resumedAt = mutableMapOf<String, Long>()
    val event = UsageEvents.Event()

    fun credit(pkg: String, from: Long, to: Long) {
        val delta = minOf(to, endMs) - maxOf(from, startMs)
        if (delta > 0) totals[pkg] = (totals[pkg] ?: 0L) + delta
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val pkg = event.packageName ?: continue
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[pkg] = event.timeStamp
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.ACTIVITY_STOPPED,
            -> resumedAt.remove(pkg)?.let { credit(pkg, it, event.timeStamp) }
        }
    }
    // Still in the foreground when the window closed.
    val cap = minOf(endMs, System.currentTimeMillis())
    for ((pkg, from) in resumedAt) credit(pkg, from, cap)
    return totals
}

private const val FOREGROUND_LOOKBACK_MS = 3L * 60 * 60 * 1000
