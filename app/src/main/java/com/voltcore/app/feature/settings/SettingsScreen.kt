package com.voltcore.app.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voltcore.app.BuildConfig
import com.voltcore.app.R
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.battery.CalibrationMode
import com.voltcore.app.core.battery.DiagnosticEntry
import com.voltcore.app.core.battery.TelemetryDiagnostics
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.service.MonitorService
import com.voltcore.app.ui.components.ArMonogram
import com.voltcore.app.ui.components.GlassCard
import com.voltcore.app.ui.components.SectionLabel
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun SettingsRoute(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = VoltSettings())
    val prefs = graph.prefs

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
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // ---------------------------------------------------------------- monitor
        item {
            GlassCard {
                SectionLabel("Live monitor")
                Spacer(Modifier.height(4.dp))
                SettingSwitch(
                    "Show ongoing notification",
                    "A live readout of current, voltage and temperature in the shade.",
                    settings.monitorEnabled,
                ) { on ->
                    scope.launch {
                        prefs.setMonitorEnabled(on)
                        if (on) MonitorService.start(context) else MonitorService.stop(context)
                    }
                }
                SettingSwitch(
                    "Only while charging",
                    "Stops the service when you unplug, so it costs nothing on battery.",
                    settings.monitorOnlyWhileCharging,
                ) { on -> scope.launch { prefs.setMonitorOnlyWhileCharging(on) } }
                SettingSlider(
                    title = "Refresh rate",
                    valueLabel = "${settings.sampleIntervalMs} ms",
                    value = settings.sampleIntervalMs.toFloat(),
                    range = 250f..5000f,
                    steps = 18,
                ) { v -> scope.launch { prefs.setSampleInterval(v.roundToInt()) } }
            }
        }

        // ---------------------------------------------------------------- alerts
        item {
            GlassCard {
                SectionLabel("Alerts")
                Spacer(Modifier.height(4.dp))
                SettingSwitch(
                    "Full charge",
                    "Tells you when it hits 100%.",
                    settings.alertOnFull,
                ) { on -> scope.launch { prefs.setAlertOnFull(on) } }

                SettingSwitch(
                    "Charge limit alarm",
                    "Alerts at a set level. VoltCore cannot stop the charger itself — " +
                        "no Android app can — so this is a reminder to unplug.",
                    settings.chargeLimitEnabled,
                ) { on -> scope.launch { prefs.setChargeLimitEnabled(on) } }
                if (settings.chargeLimitEnabled) {
                    SettingSlider(
                        title = "Limit",
                        valueLabel = "${settings.chargeLimitPercent}%",
                        value = settings.chargeLimitPercent.toFloat(),
                        range = 50f..100f,
                        steps = 9,
                    ) { v -> scope.launch { prefs.setChargeLimitPercent(v.roundToInt()) } }
                }

                SettingSwitch(
                    "Overheating",
                    "Warns when the battery gets dangerously warm.",
                    settings.alertOnOverheat,
                ) { on -> scope.launch { prefs.setAlertOnOverheat(on) } }
                if (settings.alertOnOverheat) {
                    SettingSlider(
                        title = "Threshold",
                        valueLabel = "${settings.overheatThresholdC} °C",
                        value = settings.overheatThresholdC.toFloat(),
                        range = 35f..60f,
                        steps = 24,
                    ) { v -> scope.launch { prefs.setOverheatThreshold(v.roundToInt()) } }
                }

                SettingSwitch(
                    "Low battery",
                    "Warns before you run out.",
                    settings.alertOnLowBattery,
                ) { on -> scope.launch { prefs.setAlertOnLowBattery(on) } }
                if (settings.alertOnLowBattery) {
                    SettingSlider(
                        title = "Warn at",
                        valueLabel = "${settings.lowBatteryPercent}%",
                        value = settings.lowBatteryPercent.toFloat(),
                        range = 5f..50f,
                        steps = 8,
                    ) { v -> scope.launch { prefs.setLowBatteryPercent(v.roundToInt()) } }
                }
            }
        }

        // ---------------------------------------------------------------- automation
        item {
            GlassCard {
                SectionLabel("Automatic freezing")
                Spacer(Modifier.height(4.dp))
                SettingSwitch(
                    "Freeze when the screen is off",
                    "Stops your selected apps after the phone has been idle a while. " +
                        "Needs Shizuku (optional app) and an app chosen in the Apps tab.",
                    settings.freezeOnScreenOff,
                ) { on -> scope.launch { prefs.setFreezeOnScreenOff(on) } }
                if (settings.freezeOnScreenOff) {
                    SettingSlider(
                        title = "Wait before freezing",
                        valueLabel = "${settings.freezeDelayMinutes} min",
                        value = settings.freezeDelayMinutes.toFloat(),
                        range = 0f..60f,
                        steps = 11,
                    ) { v -> scope.launch { prefs.setFreezeDelayMinutes(v.roundToInt()) } }
                    SettingSwitch(
                        "Resume when you unlock",
                        "Brings those apps back so you do not find them dead later. " +
                            "Only apps VoltCore froze automatically are resumed.",
                        settings.unfreezeOnUnlock,
                    ) { on -> scope.launch { prefs.setUnfreezeOnUnlock(on) } }
                }
            }
        }

        // ---------------------------------------------------------------- boost
        item {
            GlassCard {
                SectionLabel("What Boost does")
                Spacer(Modifier.height(4.dp))
                SettingSwitch(
                    "Stop selected apps",
                    "The main lever: background apps stop competing for power.",
                    settings.boostFreezesApps,
                ) { on -> scope.launch { prefs.setBoostFreezesApps(on) } }
                SettingSwitch(
                    "Restrict background work",
                    "Moves those apps to the restricted App Standby bucket so the system " +
                        "stops handing them jobs. Needs Shizuku.",
                    settings.boostRestrictsBuckets,
                ) { on -> scope.launch { prefs.setBoostRestrictsBuckets(on) } }
                SettingSwitch(
                    "Turn on Battery Saver",
                    "Off by default: it also throttles the app you are actually using, " +
                        "which people tend to blame on the phone.",
                    settings.boostEnablesBatterySaver,
                ) { on -> scope.launch { prefs.setBoostEnablesBatterySaver(on) } }
            }
        }

        // ---------------------------------------------------------------- display
        item {
            GlassCard {
                SectionLabel("Display")
                Spacer(Modifier.height(4.dp))
                SettingSwitch(
                    "Fahrenheit",
                    "Show temperature in °F instead of °C.",
                    settings.useFahrenheit,
                ) { on -> scope.launch { prefs.setUseFahrenheit(on) } }
                SettingSwitch(
                    "Material You colours",
                    "Repaints the app from your wallpaper. Off by default because the " +
                        "charge-speed colours are part of the readout.",
                    settings.dynamicColor,
                ) { on -> scope.launch { prefs.setDynamicColor(on) } }
                SettingSwitch(
                    "Keep screen on",
                    "Useful when watching a charge, but the screen itself draws power.",
                    settings.keepScreenOn,
                ) { on -> scope.launch { prefs.setKeepScreenOn(on) } }
            }
        }

        // ---------------------------------------------------------------- access
        item {
            GlassCard {
                SectionLabel("Permissions and access")
                Spacer(Modifier.height(4.dp))
                ActionRow(
                    "Battery Saver without Shizuku",
                    "Lets Boost turn on Battery Saver. Run once from a computer (tap to " +
                        "copy): $SECURE_SETTINGS_GRANT",
                ) {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("adb command", SECURE_SETTINGS_GRANT))
                }
                ActionRow(
                    "Shizuku (optional)",
                    "Not needed for anything except Pause, Disable and restricting " +
                        "background work — Android keeps those for system tools. Everything " +
                        "else in VoltCore works without it.",
                ) { graph.privilege.openShizuku() }
                ActionRow(
                    "Usage access",
                    "Lets VoltCore sort apps by how much you actually use them.",
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                ActionRow(
                    "Battery optimisation",
                    "Exempt VoltCore so Android does not kill the live monitor.",
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                ActionRow(
                    "Notifications",
                    "Required for the live readout and every alert.",
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- capacity
        item {
            GlassCard {
                SectionLabel("Battery capacity")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Needed for the decimal battery percentage. Your phone reports level " +
                        "only as a whole number and offers no charge counter, so VoltCore " +
                        "places you inside the current percent by measuring how much " +
                        "charge has flowed — which needs to know the battery's size.\n\n" +
                        "Enter the figure from your phone's spec sheet, or leave it on " +
                        "Auto and VoltCore works it out from how much charge passes " +
                        "between two whole-percent steps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSlider(
                    title = "Capacity",
                    valueLabel = if (settings.batteryCapacityMah <= 0) {
                        val learned = settings.learnedCapacityMah
                        if (learned > 0f) "Auto (${learned.roundToInt()} mAh)" else "Auto"
                    } else {
                        "${settings.batteryCapacityMah} mAh"
                    },
                    value = settings.batteryCapacityMah.toFloat(),
                    range = 0f..8000f,
                    steps = 79,
                ) { v ->
                    // Snap the bottom of the range to Auto: a 100 mAh phone is not a thing.
                    val mah = v.roundToInt().let { if (it < 500) 0 else it }
                    scope.launch { prefs.setBatteryCapacity(mah) }
                }
            }
        }

        // ---------------------------------------------------------------- calibration
        item {
            GlassCard {
                SectionLabel("Current calibration")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Android documents the current sensor as microamps, positive while " +
                        "charging, but a good number of manufacturers invert the sign or " +
                        "report milliamps. VoltCore works out which by watching readings " +
                        "whose direction it already knows. Override it here if the " +
                        "reading looks wrong.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CalibrationMode.entries.forEach { mode ->
                    ChoiceRow(
                        label = mode.readable(),
                        selected = settings.calibrationMode == mode,
                    ) { scope.launch { prefs.setCalibrationMode(mode) } }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Detected: " + describeCalibration(settings),
                    style = VoltText.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------------------------------------------------------- diagnostics
        item { DataSourcesCard() }

        // ---------------------------------------------------------------- data
        item {
            GlassCard {
                SectionLabel("Data")
                Spacer(Modifier.height(4.dp))
                ActionRow("Clear history", "Deletes all recorded samples and sessions.") {
                    scope.launch { graph.history.clearAll() }
                }
            }
        }

        item {
            GlassCard {
                SectionLabel("About")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArMonogram(size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}  ·  by AR",
                            style = VoltText.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Readings come from the Android battery API and, where the kernel " +
                        "allows it, directly from /sys/class/power_supply — which is how " +
                        "VoltCore can show charger voltage and the negotiated USB PD or " +
                        "Quick Charge profile. Anything a device does not report is shown " +
                        "as a dash rather than a made-up zero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(100.dp)) }
    }
}

// ----------------------------------------------------------------------- diagnostics

/**
 * What this specific phone is willing to report.
 *
 * Battery data availability differs enormously between manufacturers and fails silently —
 * Xiaomi's HyperOS, for instance, denies `/sys/class/power_supply` to every app including
 * the shell user, which alone removes charger voltage, USB PD detection, cycle count and
 * design capacity. Without this card the user just sees dashes and assumes the app is
 * broken.
 */
@Composable
private fun DataSourcesCard() {
    val context = LocalContext.current
    val colors = VoltTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }

    val entries by produceState(initialValue = emptyList<DiagnosticEntry>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { TelemetryDiagnostics(context).probe() }.getOrDefault(emptyList())
        }
    }

    GlassCard {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Device data sources")
                Spacer(Modifier.height(4.dp))
                val ok = entries.count { it.available }
                Text(
                    if (entries.isEmpty()) {
                        "Probing…"
                    } else {
                        "$ok of ${entries.size} available on this device. " +
                            "Anything unavailable shows as a dash rather than a guess."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "Hide" else "Show",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (expanded && entries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            entries.forEach { e ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        e.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        e.value ?: "unavailable",
                        style = VoltText.caption,
                        color = if (e.available) colors.good else colors.danger,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ components

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                valueLabel,
                style = VoltText.readoutSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Open",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected) "●" else "○",
            color = if (selected) accent else MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun CalibrationMode.readable(): String = when (this) {
    CalibrationMode.AUTO -> "Detect automatically"
    CalibrationMode.MICRO_AMPS -> "Microamps"
    CalibrationMode.MICRO_AMPS_INVERTED -> "Microamps, inverted sign"
    CalibrationMode.MILLI_AMPS -> "Milliamps"
    CalibrationMode.MILLI_AMPS_INVERTED -> "Milliamps, inverted sign"
}

private fun describeCalibration(settings: VoltSettings): String {
    val c = settings.calibration
    if (!c.confident) return "still learning — plug in the charger for a few seconds"
    val units = if (c.milliAmpUnits) "milliamps" else "microamps"
    val sign = if (c.signFlipped) ", sign inverted" else ""
    return "$units$sign"
}

private const val SECURE_SETTINGS_GRANT =
    "adb shell pm grant com.voltcore.app android.permission.WRITE_SECURE_SETTINGS"
