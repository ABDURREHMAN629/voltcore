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
import androidx.compose.runtime.LaunchedEffect
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

    // Reading batterystats over Shizuku is a shell round trip, so this loads once rather
    // than following the sample stream.
    LaunchedEffect(Unit) {
        usage = graph.appUsage.report(System.currentTimeMillis() - USAGE_WINDOW_MS)
    }

    HistoryScreen(
        samples = samples,
        sessions = sessions,
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
        onSetupShizuku = { graph.privilege.openShizuku() },
    )
}

@Composable
private fun HistoryScreen(
    samples: List<SampleEntity>,
    sessions: List<SessionEntity>,
    usage: UsageReport?,
    showSystem: Boolean,
    onToggleSystem: (Boolean) -> Unit,
    onGrantUsageAccess: () -> Unit,
    onSetupShizuku: () -> Unit,
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
                "The last ${WINDOW_MS / 3_600_000} hours, recorded while the monitor was running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            BatteryUsageCard(
                report = usage,
                showSystem = showSystem,
                onToggleSystem = onToggleSystem,
                onGrantUsageAccess = onGrantUsageAccess,
                onSetupShizuku = onSetupShizuku,
            )
        }

        // Each measure gets its own plot. They share a time axis but not a scale, and
        // stacking them beats a second y-axis that would invent meaning at the crossings.
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
        // One series, so the title names it and no legend box is needed.
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

// --------------------------------------------------------------------- battery usage

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
    onSetupShizuku: () -> Unit,
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
                UsageSource.BATTERY_STATS -> Badge("Measured", colors.good)
                UsageSource.USAGE_STATS -> Badge("Screen time", colors.warn)
                else -> Unit
            }
        }

        when (report?.source) {
            UsageSource.BATTERY_STATS -> {
                Text(
                    "Android's own power accounting since the last full charge, in mAh. " +
                        "Includes system components.",
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            UsageSource.USAGE_STATS -> {
                Text(
                    "This is screen time, not drain — VoltCore cannot read the real " +
                        "per-app figures without Shizuku. An app that syncs in the " +
                        "background can cost a lot and still show zero here.",
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onSetupShizuku, contentPadding = PaddingValues(0.dp)) {
                    Text("Set up Shizuku for real figures", color = colors.warn)
                }
            }

            else -> {
                Text(
                    "No usage data yet. Grant usage access for screen time, or set up " +
                        "Shizuku to read Android's real per-app battery accounting — " +
                        "neither needs root.",
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onGrantUsageAccess, contentPadding = PaddingValues(0.dp)) {
                        Text("Usage access", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onSetupShizuku, contentPadding = PaddingValues(0.dp)) {
                        Text("Shizuku", color = MaterialTheme.colorScheme.primary)
                    }
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
            // Hardware buckets have no icon; a coloured chip keeps the rows aligned.
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
                    color = accent,
                )
            }
            Spacer(Modifier.height(4.dp))
            // Proportional bar rather than a number alone: relative cost is the thing
            // you actually want to see in a ranked list.
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
