package com.voltcore.app.feature.history

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.data.AppPowerUse
import com.voltcore.app.core.data.UsageReport
import com.voltcore.app.core.data.UsageSource
import com.voltcore.app.core.data.db.SampleEntity
import com.voltcore.app.core.data.db.SessionEntity
import com.voltcore.app.core.util.Fmt
import com.voltcore.app.ui.components.AppIcon
import com.voltcore.app.ui.components.Badge
import com.voltcore.app.ui.components.ChartPoint
import com.voltcore.app.ui.components.DetailRow
import com.voltcore.app.ui.components.GlassCard
import com.voltcore.app.ui.components.LineChart
import com.voltcore.app.ui.components.SectionLabel
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun HistoryRoute(graph: AppGraph) {
    val since = remember { System.currentTimeMillis() - WINDOW_MS }
    val samples by remember { graph.history.samplesSince(since) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by remember { graph.history.recentSessions() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var usage by remember { mutableStateOf<UsageReport?>(null) }
    var showSystem by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val daily = remember { DailyUsageState(graph, scope) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { daily.load() }
        scope.launch { usage = graph.appUsage.report(System.currentTimeMillis() - USAGE_WINDOW_MS) }
    }

    HistoryScreen(
        samples = samples,
        sessions = sessions,
        daily = daily,
        usage = usage,
        showSystem = showSystem,
        onToggleSystem = { showSystem = it },
        onGrantUsageAccess = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

@Composable
private fun HistoryScreen(
    samples: List<SampleEntity>,
    sessions: List<SessionEntity>,
    daily: DailyUsageState,
    usage: UsageReport?,
    showSystem: Boolean,
    onToggleSystem: (Boolean) -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    val colors = VoltTheme.colors

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Battery use by day and by hour, what used it, and your charging sessions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { DailyUsageSection(daily, onGrantUsageAccess) }

        item {
            BatteryUsageCard(
                report = usage,
                showSystem = showSystem,
                onToggleSystem = onToggleSystem,
                onGrantUsageAccess = onGrantUsageAccess,
            )
        }

        item {
            Spacer(Modifier.height(2.dp))
            SectionLabel("Last ${WINDOW_MS / 3_600_000} hours, from the live monitor")
        }

        item {
            ChartCard(
                title = "Battery current",
                caption = "Positive is charge going in, negative is drain.",
                points = samples.mapNotNull { s -> s.currentMa?.let { ChartPoint(s.timestampMs, it) } },
                accent = colors.normal,
                includeZero = true,
                format = { "${Fmt.current(it)} ${Fmt.currentUnit(it)}" },
            )
        }

        item {
            ChartCard(
                title = "Battery level",
                caption = "Percentage over time.",
                points = samples.map { ChartPoint(it.timestampMs, it.levelPercent.toFloat()) },
                accent = colors.good,
                format = { "${it.toInt()}%" },
            )
        }

        item {
            ChartCard(
                title = "Temperature",
                caption = "Sustained highs age a battery faster than cycles do.",
                points = samples.mapNotNull { s -> s.temperatureC?.let { ChartPoint(s.timestampMs, it) } },
                accent = colors.tempWarm,
                format = { "${Fmt.temperature(it)} °C" },
            )
        }

        item {
            Spacer(Modifier.height(2.dp))
            SectionLabel("Charging sessions")
        }

        if (sessions.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        "No sessions recorded yet. Plug in the charger and VoltCore will " +
                            "log the whole session automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(sessions, key = { it.id }) { SessionCard(it) }

        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun ChartCard(
    title: String,
    caption: String,
    points: List<ChartPoint>,
    accent: androidx.compose.ui.graphics.Color,
    format: (Float) -> String,
    includeZero: Boolean = false,
) {
    GlassCard {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            caption,
            style = VoltText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        LineChart(
            points = points,
            accent = accent,
            includeZero = includeZero,
            valueFormatter = format,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Touch and hold the chart to read a point.",
            style = VoltText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SessionCard(session: SessionEntity) {
    val colors = VoltTheme.colors
    GlassCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    Fmt.clock(session.startedAtMs) + (
                        session.endedAtMs?.let { " – " + Fmt.clock(it) } ?: " – now"
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    session.plugLabel + "  ·  " + session.protocolLabel,
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.isOpen) Badge("Active", colors.good)
        }
        Spacer(Modifier.height(4.dp))
        DetailRow(
            "Level",
            session.endLevel?.let { "${session.startLevel}% → $it%" } ?: "${session.startLevel}%",
        )
        DetailRow("Duration", Fmt.duration(session.durationMs))
        DetailRow("Charge added", Fmt.mah(session.addedMah)?.let { "$it mAh" })
        DetailRow(
            "Peak current",
            Fmt.current(session.peakCurrentMa)?.let { "$it ${Fmt.currentUnit(session.peakCurrentMa)}" },
        )
        DetailRow("Peak power", Fmt.watts(session.peakPowerW)?.let { "$it W" })
        DetailRow(
            "Peak temperature",
            Fmt.temperature(session.peakTemperatureC)?.let { "$it °C" },
            valueColor = session.peakTemperatureC?.let { if (it >= 42f) colors.danger else null },
        )
    }
}

/**
 * Which apps are costing battery.
 *
 * The card leads with where the figure came from, because the two available sources mean
 * genuinely different things: `batterystats` is the system's own power attribution in mAh,
 * while UsageStats is only how long an app was on screen. Presenting screen time as though
 * it were drain would be the single most misleading thing this app could do.
 */
@Composable
private fun BatteryUsageCard(
    report: UsageReport?,
    showSystem: Boolean,
    onToggleSystem: (Boolean) -> Unit,
    onGrantUsageAccess: () -> Unit,
) {
    val colors = VoltTheme.colors

    GlassCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "What used the battery",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            when (report?.source) {
                UsageSource.BATTERY_STATS -> Badge("Android estimate", colors.normal)
                UsageSource.USAGE_STATS -> Badge("Screen time", colors.warn)
                else -> Unit
            }
        }

        when (report?.source) {
            UsageSource.BATTERY_STATS -> {
                Text(
                    "Android's own estimate for the current battery period, in mAh, " +
                        "with how much of each app's use happened off screen.",
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val coverage = report.coverage
                val drain = report.drainMah
                if (coverage != null && drain != null && coverage < COVERAGE_NOTE_BELOW) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "These rows explain ${(coverage * 100).roundToInt()}% of the " +
                            "${drain.roundToInt()} mAh used this period. Android cannot " +
                            "pin the rest on any app here: this phone does not report " +
                            "processor speeds per app, and its power profile rates the " +
                            "screen far below what screens really draw.",
                        style = VoltText.caption,
                        color = colors.warn,
                    )
                }
            }

            UsageSource.USAGE_STATS -> {
                Text(
                    "This is screen time, not drain. Android's per-app battery figures " +
                        "need the one-time USB permission shown under \"Fill in more " +
                        "history\" above. Until then, an app that syncs in the background " +
                        "can cost a lot and still show zero here.",
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    "No usage data yet. Allow Usage access for screen time, or make the " +
                        "one-time USB grant above for Android's per-app battery estimate — " +
                        "neither needs root or another app.",
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onGrantUsageAccess, contentPadding = PaddingValues(0.dp)) {
                    Text("Usage access", color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        val all = report?.entries.orEmpty()
        if (all.isEmpty()) return@GlassCard

        val rows = (if (showSystem) all else all.filter { !it.isSystem }).take(MAX_USAGE_ROWS)
        val peak = rows.maxOfOrNull { it.weight() } ?: 1.0

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("${rows.size} of ${all.size}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "System apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Switch(checked = showSystem, onCheckedChange = onToggleSystem)
            }
        }
        Spacer(Modifier.height(4.dp))

        rows.forEach { entry -> UsageRow(entry, peak) }
    }
}

@Composable
private fun UsageRow(entry: AppPowerUse, peak: Double) {
    val colors = VoltTheme.colors
    val fraction = if (peak > 0.0) (entry.weight() / peak).toFloat().coerceIn(0f, 1f) else 0f
    val accent = if (entry.isSystemBucket) colors.trickle else colors.drain

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.packageName != null) {
            AppIcon(entry.packageName, entry.label, size = 30.dp)
        } else {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.20f)),
            )
        }
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.displayValue(),
                    style = VoltText.readoutSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            entry.offScreenShare?.let { share ->
                val pct = (share * 100).roundToInt()
                Text(
                    "$pct% off screen",
                    style = VoltText.caption,
                    color = if (share >= HEAVY_OFF_SCREEN) {
                        colors.warn
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
        }
    }
}

private fun AppPowerUse.weight(): Double = mah ?: (foregroundMs?.toDouble() ?: 0.0)

private fun AppPowerUse.displayValue(): String {
    mah?.let { return String.format(java.util.Locale.US, "%.1f mAh", it) }
    foregroundMs?.let { return Fmt.duration(it) ?: "—" }
    return "—"
}

private const val WINDOW_MS = 6L * 60 * 60 * 1000
private const val USAGE_WINDOW_MS = 24L * 60 * 60 * 1000
private const val MAX_USAGE_ROWS = 12

/** Below this share of the drain explained, the card says what it cannot see. */
private const val COVERAGE_NOTE_BELOW = 0.9

/** Off-screen share at which an app is flagged as a background drainer. */
private const val HEAVY_OFF_SCREEN = 0.5
