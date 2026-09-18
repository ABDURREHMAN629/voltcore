package com.voltcore.app.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voltcore.app.core.data.DayUsage
import com.voltcore.app.core.data.HourUsage
import com.voltcore.app.core.util.Fmt
import com.voltcore.app.ui.components.AppIcon
import com.voltcore.app.ui.components.DayBarChart
import com.voltcore.app.ui.components.DetailRow
import com.voltcore.app.ui.components.GlassCard
import com.voltcore.app.ui.components.HourBarChart
import com.voltcore.app.ui.components.SectionLabel
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The day and hour battery views, plus the month summary and the access explanation. */
@Composable
fun DailyUsageSection(
    state: DailyUsageState,
    onGrantUsageAccess: () -> Unit,
) {
    val colors = VoltTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DayCard(state, colors.drain)
        state.selectedDay?.let { day ->
            HourCard(state, day, colors.drain, colors.normal)
        }
        MonthCard(state)
        AccessCard(state, onGrantUsageAccess)
    }
}

// ---------------------------------------------------------------------------- days

@Composable
private fun DayCard(state: DailyUsageState, barColor: Color) {
    val visible = state.visibleDays
    GlassCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Battery by day",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (visible.isNotEmpty()) {
                    Text(
                        "${shortDate(visible.first().date)} – ${shortDate(visible.last().date)}" +
                            "  ·  tap a day",
                        style = VoltText.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.importing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            PageButton("‹", state.canPageBack) { state.page(forward = false) }
            PageButton("›", state.canPageForward) { state.page(forward = true) }
        }
        Spacer(Modifier.height(10.dp))

        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else {
            DayBarChart(
                days = visible,
                selected = visible.indexOfFirst { it.date == state.selectedDate }.takeIf { it >= 0 },
                onSelect = { i -> state.selectDate(visible[i].date) },
                barColor = barColor,
            )
        }

        state.selectedDay?.let { day ->
            Spacer(Modifier.height(8.dp))
            DaySummary(day, state)
        }
    }
}

@Composable
private fun DaySummary(day: DayUsage, state: DailyUsageState) {
    Text(
        longDate(day.date),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (day.hasData) {
        DetailRow("Battery used", pctWithMah(day.usedPct, state.capacityMah))
        DetailRow("Charged", "${day.chargedPct.roundToInt()}%")
        DetailRow("Time on battery", Fmt.duration(day.onBatteryMs) ?: "—")
    } else {
        Text(
            "No battery readings for this day.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.screenByHour?.let { perHour ->
        DetailRow("Screen on", Fmt.duration(perHour.sum()) ?: "0m")
    }
    if (day.onBatteryMs >= HOUR_MS) {
        val rate = day.usedPct / (day.onBatteryMs.toFloat() / HOUR_MS)
        DetailRow("Average drain", "%.1f%% per hour".format(rate))
    }
}

// --------------------------------------------------------------------------- hours

@Composable
private fun HourCard(state: DailyUsageState, day: DayUsage, usedColor: Color, chargedColor: Color) {
    GlassCard {
        Text(
            "Hour by hour",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${longDate(day.date)}  ·  tap an hour",
            style = VoltText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Two series, so a legend — and text labels, so identity never rests on colour alone.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendKey(usedColor, "Used (down)")
            LegendKey(chargedColor, "Charged (up)")
        }
        Spacer(Modifier.height(6.dp))
        HourBarChart(
            hours = day.hours,
            selected = state.selectedHour,
            onSelect = state::selectHour,
            usedColor = usedColor,
            chargedColor = chargedColor,
        )
        state.selectedHour?.let { h ->
            Spacer(Modifier.height(10.dp))
            HourDetail(state, day.hours[h])
        }
    }
}

@Composable
private fun HourDetail(state: DailyUsageState, hour: HourUsage) {
    Text(
        "%02d:00 – %02d:00".format(hour.hour, (hour.hour + 1) % 24),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    // Screen time comes from Android's usage log, not the battery log, so it is shown even
    // for an hour with no battery readings.
    if (hour.hasData) {
        DetailRow("Battery used", pctWithMah(hour.usedPct, state.capacityMah, decimals = 1))
        if (hour.chargedPct > 0f) DetailRow("Charged", "%.1f%%".format(hour.chargedPct))
        DetailRow("On battery", "${(hour.onBatteryMs / 60_000).toInt()} min")
    } else {
        Text(
            "No battery readings for this hour.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.screenByHour?.let { perHour ->
        DetailRow("Screen on", "${(perHour[hour.hour] / 60_000).toInt()} min")
    }

    val apps = state.hourApps
    when {
        !state.usageAccess -> Unit // explained in the access card
        apps == null -> Unit
        apps.isEmpty() -> Text(
            "No app was on screen for more than 30 seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            Spacer(Modifier.height(6.dp))
            SectionLabel("Apps on screen this hour")
            Spacer(Modifier.height(4.dp))
            apps.forEach { app ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.packageName, app.label, size = 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(app.foregroundMs / 60_000).coerceAtLeast(1)} min",
                        style = VoltText.readoutSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Text(
                "Screen time, not battery — Android does not split an hour's drain by app.",
                style = VoltText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------------- month

@Composable
private fun MonthCard(state: DailyUsageState) {
    val today = LocalDate.now()
    val month = state.days.filter { it.date.year == today.year && it.date.month == today.month }
    val recorded = month.filter { it.hasData }
    if (recorded.isEmpty()) return

    val used = recorded.sumOf { it.usedPct.toDouble() }.toFloat()
    val perDay = used / recorded.size
    GlassCard {
        Text(
            today.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " so far",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "${recorded.size} day${if (recorded.size == 1) "" else "s"} with readings",
            style = VoltText.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        DetailRow("Battery used", pctWithMah(used, state.capacityMah))
        DetailRow("Full charges' worth", "%.1f".format(used / 100f))
        DetailRow("Average per day", "${perDay.roundToInt()}%")
        // A projection, and labelled as one: Android keeps only about two weeks of history.
        DetailRow("30-day pace", "≈ %.0f full charges".format(perDay * 30 / 100f))
    }
}

// -------------------------------------------------------------------------- access

@Composable
private fun AccessCard(state: DailyUsageState, onGrantUsageAccess: () -> Unit) {
    val coverage = state.coverage ?: return
    if (coverage.dumpGranted && state.usageAccess) return

    GlassCard(accent = VoltTheme.colors.warn) {
        SectionLabel("Fill in more history")
        Spacer(Modifier.height(6.dp))

        if (!coverage.dumpGranted) {
            val since = coverage.firstMs?.let { shortDate(epochToDate(it)) }
            Text(
                (since?.let { "VoltCore has been recording since $it. " } ?: "") +
                    "Android itself keeps about two weeks of battery history, including days " +
                    "before VoltCore was installed. To let VoltCore read it, connect the phone " +
                    "to a computer once and run these two lines — no other app is needed, and " +
                    "they stay granted after a reboot:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GRANT_COMMANDS.forEach { command ->
                CopyableCommand(command)
                Spacer(Modifier.height(6.dp))
            }
        }

        if (!state.usageAccess) {
            if (!coverage.dumpGranted) Spacer(Modifier.height(12.dp))
            Text(
                "Screen time and apps for each hour need Usage access — a switch in your " +
                    "phone's own settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onGrantUsageAccess, contentPadding = PaddingValues(0.dp)) {
                Text("Open Usage access", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CopyableCommand(command: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("adb command", command))
            copied = true
        }) {
            Text(if (copied) "Copied" else "Copy", color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ------------------------------------------------------------------------- helpers

@Composable
private fun PageButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = VoltText.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "76%  ·  ≈ 3,790 mAh" — the mAh half only when the battery's size is known. */
private fun pctWithMah(pct: Float, capacityMah: Float?, decimals: Int = 0): String {
    val p = if (decimals == 0) "${pct.roundToInt()}%" else "%.${decimals}f%%".format(pct)
    val mah = capacityMah?.let { Fmt.mah(pct / 100f * it) }
    return if (mah != null) "$p  ·  ≈ $mah mAh" else p
}

private fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM"))
private fun longDate(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))
    }
}

private fun epochToDate(ms: Long): LocalDate =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

private const val HOUR_MS = 60L * 60 * 1000
private val GRANT_COMMANDS = listOf(
    "adb shell pm grant com.voltcore.app android.permission.DUMP",
    "adb shell pm grant com.voltcore.app android.permission.PACKAGE_USAGE_STATS",
)
