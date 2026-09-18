package com.voltcore.app.core.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.voltcore.app.core.data.db.LevelLogEntity
import com.voltcore.app.core.data.db.VoltDatabase
import com.voltcore.app.core.privileged.LocalDumpsys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Battery activity within one clock hour. */
data class HourUsage(
    val hour: Int,
    /** Percentage points drained while unplugged. */
    val usedPct: Float,
    /** Percentage points gained while plugged in. */
    val chargedPct: Float,
    val onBatteryMs: Long,
    val pluggedMs: Long,
) {
    val hasData: Boolean get() = onBatteryMs + pluggedMs > 0
}

/** One calendar day, broken into 24 hours. */
data class DayUsage(
    val date: LocalDate,
    val hours: List<HourUsage>,
) {
    val usedPct: Float get() = hours.sumOf { it.usedPct.toDouble() }.toFloat()
    val chargedPct: Float get() = hours.sumOf { it.chargedPct.toDouble() }.toFloat()
    val onBatteryMs: Long get() = hours.sumOf { it.onBatteryMs }
    val hasData: Boolean get() = hours.any { it.hasData }
}

/** How far back the log reaches and where it came from, so the UI can say so. */
data class LogCoverage(
    val firstMs: Long?,
    val hasAndroidHistory: Boolean,
    val dumpGranted: Boolean,
)

/**
 * The battery-level log behind the day and hour views.
 *
 * Two sources feed it:
 *
 *  - **VoltCore's own readings.** A reading every 15 minutes from a background job, plus one
 *    at every plug and unplug. Needs no permission at all, so it always works — but only from
 *    the day VoltCore was installed.
 *  - **Android's battery history.** Android keeps about two weeks of level changes, including
 *    from before VoltCore existed. Reading it needs `DUMP`, granted once over USB (see
 *    [LocalDumpsys]). Only the moments where level, plug or screen state changed are kept, so
 *    hundreds of thousands of history events become a few thousand rows.
 */
class BatteryLogRepository(
    private val context: Context,
    private val dumpsys: LocalDumpsys,
) {

    private val dao = VoltDatabase.get(context).dao()
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val importLock = Mutex()

    /**
     * When the last import was attempted. On disk, not in a field: the background job runs in
     * a process Android freely kills between runs, and an in-memory timestamp would reset to
     * zero each time — turning a once-a-day import of tens of megabytes into one every 15
     * minutes.
     */
    private val state = context.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
    private var lastImportAttemptMs: Long
        get() = state.getLong(KEY_LAST_IMPORT, 0L)
        set(value) = state.edit().putLong(KEY_LAST_IMPORT, value).apply()

    // ------------------------------------------------------------------ recording

    /** Logs the level right now. Cheap: reads the sticky broadcast, writes one row. */
    suspend fun recordNow() = withContext(Dispatchers.IO) {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return@withContext
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (raw < 0 || scale <= 0) return@withContext
        record(
            level = raw * 100 / scale,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }

    /** Logs a level already in hand, e.g. from the live monitor. */
    suspend fun record(level: Int, plugged: Boolean, atMs: Long = System.currentTimeMillis()) {
        // The monitor, the worker and the History screen all log; drop an unchanged reading
        // that lands soon after the last one. Periodic repeats still bridge long idle gaps.
        val last = dao.lastLevel()
        if (last != null && last.level == level && last.plugged == plugged &&
            atMs - last.timestampMs in 0 until REPEAT_MIN_INTERVAL_MS
        ) return
        dao.insertLevels(
            listOf(
                LevelLogEntity(
                    timestampMs = atMs,
                    level = level.coerceIn(0, 100),
                    plugged = plugged,
                    screenOn = null,
                    source = LevelLogEntity.SOURCE_VOLTCORE,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ Android history

    /**
     * Imports Android's battery history if `DUMP` has been granted.
     *
     * @param minIntervalMs skip if an import was attempted more recently than this; the dump
     *        is tens of megabytes and there is no point parsing it twice in a few minutes.
     * @return the number of rows written, or null when the permission is missing or the import
     *         was skipped.
     */
    suspend fun importAndroidHistory(minIntervalMs: Long = DEFAULT_IMPORT_INTERVAL_MS): Int? =
        importLock.withLock {
            val now = System.currentTimeMillis()
            if (now - lastImportAttemptMs < minIntervalMs) return@withLock null
            if (!dumpsys.isGranted()) return@withLock null

            val rows = dumpsys.stream(listOf("batterystats", "--history")) { lines ->
                parseHistory(lines, now)
            } ?: return@withLock null
            // Only a dump that actually ran counts, so a refusal is retried next time.
            lastImportAttemptMs = now
            Log.i(TAG, "Imported ${rows.size} history rows")

            rows.chunked(INSERT_BATCH).forEach { dao.insertLevels(it) }
            dao.pruneLevels(now - RETENTION_MS)
            rows.size
        }

    /**
     * Keeps only the history lines where something the day view cares about changed.
     *
     * History lines look like `  09-05 11:33:09.802 076 status=charging plug=usb +screen ...`:
     * a local timestamp without a year, the level, then state flags. The year is inferred —
     * history spans weeks, so it is this year unless that would put it in the future.
     */
    private fun parseHistory(lines: Sequence<String>, nowMs: Long): List<LevelLogEntity> {
        val out = ArrayList<LevelLogEntity>(4096)
        val thisYear = LocalDate.now(zone).year
        var plugged: Boolean? = null
        var screen: Boolean? = null
        var lastLevel = -1
        var lastPlugged: Boolean? = null
        var lastScreen: Boolean? = null

        for (line in lines) {
            val m = HISTORY_LINE.matchEntire(line) ?: continue
            val (mon, day, hh, mm, ss, ms, lvl, rest) = m.destructured

            PLUG.find(rest)?.let { plugged = it.groupValues[1] != "none" }
            if (PLUGGED_ON.containsMatchIn(rest)) plugged = true
            if (PLUGGED_OFF.containsMatchIn(rest)) plugged = false
            if (SCREEN_ON.containsMatchIn(rest)) screen = true
            if (SCREEN_OFF.containsMatchIn(rest)) screen = false

            val level = lvl.toInt()
            val p = plugged ?: continue // state unknown until the first status line
            if (level == lastLevel && p == lastPlugged && screen == lastScreen) continue

            val t = runCatching {
                var dt = LocalDateTime.of(thisYear, mon.toInt(), day.toInt(), hh.toInt(), mm.toInt(), ss.toInt())
                    .plusNanos(ms.toLong() * 1_000_000)
                if (dt.atZone(zone).toInstant().toEpochMilli() > nowMs + FUTURE_TOLERANCE_MS) {
                    dt = dt.minusYears(1)
                }
                dt.atZone(zone).toInstant().toEpochMilli()
            }.getOrNull() ?: continue

            out += LevelLogEntity(t, level, p, screen, LevelLogEntity.SOURCE_ANDROID)
            lastLevel = level
            lastPlugged = p
            lastScreen = screen
        }
        return out
    }

    // ------------------------------------------------------------------ reading

    suspend fun coverage(): LogCoverage = withContext(Dispatchers.IO) {
        LogCoverage(
            firstMs = dao.firstLevelTimestamp(),
            hasAndroidHistory = dao.lastLevelTimestamp(LevelLogEntity.SOURCE_ANDROID) != null,
            dumpGranted = dumpsys.isGranted(),
        )
    }

    /** Usage for each of the [days] days ending today, oldest first. */
    suspend fun days(days: Int, today: LocalDate = LocalDate.now(zone)): List<DayUsage> =
        withContext(Dispatchers.IO) {
            val first = today.minusDays((days - 1).toLong())
            val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // One reading from before the window, so its first hour has a starting level.
            val rows = dao.levelsBetween(from - MAX_GAP_MS, to)
            val buckets = bucketByHour(rows, from, to)
            (0 until days).map { i ->
                val date = first.plusDays(i.toLong())
                DayUsage(date, (0 until 24).map { h -> buckets[date to h] ?: HourUsage(h, 0f, 0f, 0, 0) })
            }
        }

    /**
     * Spreads every gap between two readings across the clock hours it covers.
     *
     * The gap takes the plug state of its first reading, and its level change is shared out in
     * proportion to time. With Android's history the gaps are seconds long and this is exact;
     * with VoltCore's 15-minute readings it avoids piling a whole reading's drop onto whichever
     * hour happened to contain it. Gaps longer than [MAX_GAP_MS] are dropped — the phone was
     * probably off, and inventing a slope across them would be a guess.
     */
    private fun bucketByHour(rows: List<LevelLogEntity>, from: Long, to: Long): Map<Pair<LocalDate, Int>, HourUsage> {
        data class Acc(var used: Double = 0.0, var charged: Double = 0.0, var bat: Long = 0, var plug: Long = 0)
        val acc = HashMap<Pair<LocalDate, Int>, Acc>()

        for (i in 0 until rows.size - 1) {
            val a = rows[i]
            val b = rows[i + 1]
            val dt = b.timestampMs - a.timestampMs
            if (dt <= 0 || dt > MAX_GAP_MS) continue
            val delta = (b.level - a.level).toDouble()

            var s = maxOf(a.timestampMs, from)
            val end = minOf(b.timestampMs, to)
            while (s < end) {
                val local = Instant.ofEpochMilli(s).atZone(zone)
                val hourEnd = local.withMinute(0).withSecond(0).withNano(0).plusHours(1)
                    .toInstant().toEpochMilli()
                val e = minOf(hourEnd, end)
                val span = e - s
                val frac = span.toDouble() / dt
                val bucket = acc.getOrPut(local.toLocalDate() to local.hour) { Acc() }
                if (a.plugged) {
                    bucket.plug += span
                    if (delta > 0) bucket.charged += delta * frac
                } else {
                    bucket.bat += span
                    if (delta < 0) bucket.used += -delta * frac
                }
                s = e
            }
        }
        return acc.mapValues { (k, v) ->
            HourUsage(k.second, v.used.toFloat(), v.charged.toFloat(), v.bat, v.plug)
        }
    }

    private companion object {
        const val TAG = "VoltCoreBatteryLog"
        const val STATE_FILE = "battery_log"
        const val KEY_LAST_IMPORT = "last_import_attempt"

        val HISTORY_LINE = Regex("""\s+(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.(\d{3}) (\d{3}) (.*)""")
        val PLUG = Regex("""plug=(\w+)""")
        val PLUGGED_ON = Regex("""(^|\s)\+plugged""")
        val PLUGGED_OFF = Regex("""(^|\s)-plugged""")
        val SCREEN_ON = Regex("""(^|\s)\+screen(\s|$)""")
        val SCREEN_OFF = Regex("""(^|\s)-screen(\s|$)""")

        const val MAX_GAP_MS = 3L * 60 * 60 * 1000
        private const val REPEAT_MIN_INTERVAL_MS = 10L * 60 * 1000
        const val DEFAULT_IMPORT_INTERVAL_MS = 30L * 60 * 1000
        const val FUTURE_TOLERANCE_MS = 24L * 60 * 60 * 1000
        const val RETENTION_MS = 120L * 24 * 60 * 60 * 1000
        const val INSERT_BATCH = 500
    }
}
