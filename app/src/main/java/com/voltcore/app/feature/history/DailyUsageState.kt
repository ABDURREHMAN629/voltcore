package com.voltcore.app.feature.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.data.AppScreenTime
import com.voltcore.app.core.data.DayUsage
import com.voltcore.app.core.data.LogCoverage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** State for the day and hour battery views. */
class DailyUsageState(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    var days by mutableStateOf<List<DayUsage>>(emptyList())
        private set
    var coverage by mutableStateOf<LogCoverage?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var importing by mutableStateOf(false)
        private set
    var usageAccess by mutableStateOf(false)
        private set
    var capacityMah by mutableStateOf<Float?>(null)
        private set

    /** The last day shown in the day chart; the chart shows [PAGE_DAYS] ending here. */
    var pageEnd by mutableStateOf(LocalDate.now())
        private set
    var selectedDate by mutableStateOf<LocalDate?>(null)
        private set
    var selectedHour by mutableStateOf<Int?>(null)
        private set

    /** Screen-on ms per hour for [selectedDate]; null without usage access. */
    var screenByHour by mutableStateOf<LongArray?>(null)
        private set
    var hourApps by mutableStateOf<List<AppScreenTime>?>(null)
        private set

    private var dayJob: Job? = null
    private var hourJob: Job? = null

    val visibleDays: List<DayUsage>
        get() {
            val first = pageEnd.minusDays((PAGE_DAYS - 1).toLong())
            return days.filter { !it.date.isBefore(first) && !it.date.isAfter(pageEnd) }
        }

    val selectedDay: DayUsage? get() = days.firstOrNull { it.date == selectedDate }

    val canPageBack: Boolean
        get() = days.isNotEmpty() && pageEnd.minusDays(PAGE_DAYS.toLong()) >= days.first().date
    val canPageForward: Boolean get() = pageEnd.isBefore(LocalDate.now())

    suspend fun load() {
        loading = true
        usageAccess = graph.screenTime.hasUsageAccess()
        capacityMah = resolveCapacity()
        coverage = graph.batteryLog.coverage()

        // Show what is already stored straight away, then import Android's history if it is
        // permitted and refresh — the import can take a few seconds.
        reloadDays()
        loading = false

        if (coverage?.dumpGranted == true) {
            importing = true
            val written = runCatching { graph.batteryLog.importAndroidHistory() }.getOrNull()
            importing = false
            if (written != null) {
                coverage = graph.batteryLog.coverage()
                reloadDays()
            }
        }
    }

    private suspend fun reloadDays() {
        runCatching { graph.batteryLog.recordNow() }
        days = graph.batteryLog.days(LOADED_DAYS)
        val keep = selectedDate?.takeIf { d -> days.any { it.date == d } }
        if (keep == null) {
            val today = LocalDate.now()
            selectDate(today, hour = LocalTime.now().hour)
        } else {
            selectDate(keep, hour = selectedHour)
        }
    }

    fun selectDate(date: LocalDate, hour: Int? = null) {
        selectedDate = date
        val day = days.firstOrNull { it.date == date }
        // Default to the current hour for today, otherwise the day's heaviest hour.
        selectedHour = hour
            ?: LocalTime.now().hour.takeIf { date == LocalDate.now() }
            ?: day?.hours?.maxByOrNull { it.usedPct }?.takeIf { it.usedPct > 0f }?.hour
            ?: day?.hours?.firstOrNull { it.hasData }?.hour
            ?: NOON
        screenByHour = null
        dayJob?.cancel()
        dayJob = scope.launch { screenByHour = graph.screenTime.screenOnByHour(date) }
        loadHourApps()
    }

    fun selectHour(hour: Int) {
        selectedHour = hour
        loadHourApps()
    }

    fun page(forward: Boolean) {
        val next = if (forward) pageEnd.plusDays(PAGE_DAYS.toLong()) else pageEnd.minusDays(PAGE_DAYS.toLong())
        pageEnd = minOf(next, LocalDate.now())
    }

    private fun loadHourApps() {
        hourJob?.cancel()
        hourApps = null
        val date = selectedDate ?: return
        val hour = selectedHour ?: return
        val zone = ZoneId.systemDefault()
        val start = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        hourJob = scope.launch {
            hourApps = graph.screenTime.appsBetween(start, start + HOUR_MS)
        }
    }

    /** User's figure, then the learned one, then the factory rating read from the phone. */
    private suspend fun resolveCapacity(): Float? {
        graph.telemetry.levelEstimator.capacityMah?.let { return it }
        // A reading seeds the estimator with the factory capacity. It does file and binder
        // I/O, so it must not run on the main thread this is called from.
        withContext(Dispatchers.IO) { runCatching { graph.telemetry.read() } }
        return graph.telemetry.levelEstimator.capacityMah
    }

    companion object {
        const val PAGE_DAYS = 14
        private const val LOADED_DAYS = 90
        private const val HOUR_MS = 60L * 60 * 1000
        private const val NOON = 12
    }
}
