package com.voltcore.app.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.core.model.BatterySnapshot
import com.voltcore.app.core.model.ChargeProtocol
import com.voltcore.app.core.model.ChargeSpeed
import com.voltcore.app.core.model.ChargeStatus
import com.voltcore.app.core.util.Fmt
import com.voltcore.app.ui.components.ArMonogram
import com.voltcore.app.ui.components.Badge
import com.voltcore.app.ui.components.ChargeRing
import com.voltcore.app.ui.components.DetailRow
import com.voltcore.app.ui.components.GlassCard
import com.voltcore.app.ui.components.SectionLabel
import com.voltcore.app.ui.components.StatTile
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme
import kotlinx.coroutines.flow.Flow

@Composable
fun DashboardRoute(graph: AppGraph, settings: VoltSettings) {
    val telemetry = graph.telemetry
    val flow: Flow<BatterySnapshot> = remember(telemetry, settings.sampleIntervalMs) {
        telemetry.stream(intervalMs = settings.sampleIntervalMs.toLong())
    }
    val snapshot by flow.collectAsStateWithLifecycle(initialValue = BatterySnapshot())
    DashboardScreen(snapshot, settings)
}

@Composable
fun DashboardScreen(snapshot: BatterySnapshot, settings: VoltSettings = VoltSettings()) {
    val colors = VoltTheme.colors
    val accent = speedColor(snapshot.speed, snapshot.status)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(
                // A faint wash of the accent behind everything, so the whole screen
                // shifts colour as charging speed changes.
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = if (colors.isDark) 0.13f else 0.07f), Color.Transparent),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 18.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Header(snapshot, accent)
            Spacer(Modifier.height(10.dp))
            HeroRing(snapshot, accent)
            Spacer(Modifier.height(18.dp))
            LiveStats(snapshot, accent, settings)
            Spacer(Modifier.height(12.dp))
            ChargerCard(snapshot, accent)
            Spacer(Modifier.height(12.dp))
            HealthCard(snapshot)
            // The nav bar floats over the content, so the scroll must be able to run
            // past it or the last card can never be fully read.
            Spacer(Modifier.height(110.dp))
        }
    }
}

// ---------------------------------------------------------------------------- header

@Composable
private fun Header(snapshot: BatterySnapshot, accent: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The monogram carries the "AR" of the app's name, so the title beside it
            // stays "VoltCore" rather than reading "AR" twice.
            ArMonogram()
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "VoltCore",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    statusLine(snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Badge(snapshot.speed.label, accent)
    }
}

private fun statusLine(s: BatterySnapshot): String = when (s.status) {
    ChargeStatus.CHARGING -> "Charging over ${s.plug.label.lowercase()}"
    ChargeStatus.FULL -> "Fully charged"
    ChargeStatus.NOT_CHARGING -> "Plugged in, not charging"
    ChargeStatus.DISCHARGING -> "On battery"
    ChargeStatus.UNKNOWN -> "Reading battery…"
}

// ------------------------------------------------------------------------------ ring

@Composable
private fun HeroRing(snapshot: BatterySnapshot, accent: Color) {
    val level by animateFloatAsState(
        targetValue = snapshot.levelPercent / 100f,
        animationSpec = tween(900),
        label = "level",
    )
    val animatedAccent by animateColorAsState(accent, tween(700), label = "accent")

    val current = snapshot.currentMa
    val speedFraction = ((snapshot.inputPowerW ?: snapshot.batteryPowerW ?: 0f) / 45f)
        .coerceIn(0f, 1f)

    // The ring itself stays square so its centre — and therefore the readout inside it —
    // lands where you expect. The container is deliberately shorter, because a
    // 270-degree arc leaves its bottom quadrant empty and reserving a full square for it
    // pushed the stat tiles a long way down the screen for nothing.
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.32f),
        contentAlignment = Alignment.TopCenter,
    ) {
        ChargeRing(
            level = level,
            accent = animatedAccent,
            animate = snapshot.isCharging,
            chargeSpeedFraction = speedFraction,
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .aspectRatio(1f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = Fmt.current(current, showSign = true) ?: "—",
                        style = VoltText.hero,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = Fmt.currentUnit(current),
                        style = VoltText.unit,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 14.dp, start = 4.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        Fmt.watts(snapshot.batteryPowerW)?.let { "$it W" },
                        buildString {
                            append(Fmt.level(snapshot.levelPrecise, snapshot.levelPercent))
                            append('%')
                            // A decimal derived by counting coulombs is an estimate, and
                            // saying so is the difference between a readout and a claim.
                            if (snapshot.levelIsEstimated) append(" est.")
                        },
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------- stats

@Composable
private fun LiveStats(snapshot: BatterySnapshot, accent: Color, settings: VoltSettings) {
    val colors = VoltTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = if (snapshot.levelIsEstimated) "Level (est.)" else "Battery level",
                value = Fmt.level(snapshot.levelPrecise, snapshot.levelPercent),
                unit = "%",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Health",
                value = snapshot.healthPercent?.toString(),
                unit = "%",
                accent = snapshot.healthPercent?.let { healthColor(it, colors) } ?: accent,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Voltage",
                value = Fmt.volts(snapshot.voltageV),
                unit = "V",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Temperature",
                value = Fmt.temperature(snapshot.temperatureC, settings.useFahrenheit),
                unit = Fmt.temperatureUnit(settings.useFahrenheit),
                accent = tempColor(snapshot.temperatureC, colors),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                label = "Battery power",
                value = Fmt.watts(snapshot.batteryPowerW),
                unit = "W",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Charge left",
                value = Fmt.mah(snapshot.chargeCounterMah),
                unit = "mAh",
                accent = accent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// --------------------------------------------------------------------------- charger

@Composable
private fun ChargerCard(snapshot: BatterySnapshot, accent: Color) {
    if (!snapshot.status.isCharging && snapshot.chargerVoltageV == null) return

    GlassCard(accent = accent) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Charger")
            if (snapshot.protocol != ChargeProtocol.UNKNOWN) {
                Badge(snapshot.protocol.label, accent)
            }
        }
        Spacer(Modifier.height(6.dp))
        DetailRow("Input voltage", Fmt.volts(snapshot.chargerVoltageV)?.let { "$it V" })
        DetailRow("Input current", Fmt.current(snapshot.chargerCurrentMa)?.let {
            "$it ${Fmt.currentUnit(snapshot.chargerCurrentMa)}"
        })
        DetailRow(
            "Input power",
            Fmt.watts(snapshot.inputPowerW)?.let { "$it W" },
            valueColor = accent,
        )
        DetailRow("Source", snapshot.plug.label)
    }
}

// ---------------------------------------------------------------------------- health

@Composable
private fun HealthCard(snapshot: BatterySnapshot) {
    val colors = VoltTheme.colors
    GlassCard {
        SectionLabel("Battery health")
        Spacer(Modifier.height(6.dp))
        DetailRow(
            "Capacity now",
            Fmt.mah(snapshot.fullCapacityMah)?.let { "$it mAh" },
        )
        DetailRow(
            "Design capacity",
            Fmt.mah(snapshot.designCapacityMah)?.let { "$it mAh" },
        )
        DetailRow(
            "Health",
            snapshot.healthPercent?.let { "$it%" },
            valueColor = snapshot.healthPercent?.let { healthColor(it, colors) },
        )
        DetailRow("Charge cycles", snapshot.cycleCount?.toString())
        DetailRow("Condition", snapshot.health.label)
        DetailRow("Technology", snapshot.technology)
    }
}

// ----------------------------------------------------------------------------- colour

@Composable
private fun speedColor(speed: ChargeSpeed, status: ChargeStatus): Color {
    val c = VoltTheme.colors
    if (!status.isCharging) return c.drain
    return when (speed) {
        ChargeSpeed.NONE -> c.trickle
        ChargeSpeed.TRICKLE -> c.trickle
        ChargeSpeed.SLOW -> c.slow
        ChargeSpeed.NORMAL -> c.normal
        ChargeSpeed.FAST -> c.fast
        ChargeSpeed.ULTRA -> c.ultra
    }
}

private fun tempColor(celsius: Float?, c: com.voltcore.app.ui.theme.VoltColors): Color = when {
    celsius == null -> c.tempMild
    celsius < 15f -> c.tempCold
    celsius < 35f -> c.tempMild
    celsius < 41f -> c.tempWarm
    else -> c.tempHot
}

private fun healthColor(percent: Int, c: com.voltcore.app.ui.theme.VoltColors): Color = when {
    percent >= 85 -> c.good
    percent >= 70 -> c.warn
    else -> c.danger
}
