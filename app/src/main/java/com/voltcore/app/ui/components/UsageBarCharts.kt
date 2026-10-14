package com.voltcore.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voltcore.app.core.data.DayUsage
import com.voltcore.app.core.data.HourUsage
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Battery used per day, one bar per day.
 *
 * A day with no readings at all is drawn as a small hollow marker rather than a zero-height
 * bar: "nothing was recorded" and "nothing was used" are different claims, and a flat bar
 * would make the first look like the second.
 */
@Composable
fun DayBarChart(
    days: List<DayUsage>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier
            .fillMaxWidth()
            .height(170.dp)
            .pointerInput(days.size) {
                detectTapGestures { p ->
                    if (days.isEmpty()) return@detectTapGestures
                    val i = (p.x / (size.width / days.size)).toInt().coerceIn(0, days.size - 1)
                    onSelect(i)
                }
            },
    ) {
        if (days.isEmpty()) return@Canvas
        val labelH = 30.dp.toPx()
        val topPad = 18.dp.toPx()
        val plotH = size.height - labelH - topPad
        val baseline = topPad + plotH
        val slot = size.width / days.size
        val barW = (slot * 0.62f).coerceAtMost(18.dp.toPx())
        val peak = days.maxOf { it.usedPct }.coerceAtLeast(MIN_DAY_SCALE)

        drawLine(track, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())

        days.forEachIndexed { i, day ->
            val cx = slot * i + slot / 2
            val isSel = i == selected
            val dim = selected != null && !isSel
            if (!day.hasData) {
                drawCircle(muted.copy(alpha = 0.35f), 3.dp.toPx(), Offset(cx, baseline - 4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            } else {
                val h = (day.usedPct / peak) * plotH
                roundedBar(cx - barW / 2, baseline - h, barW, h, roundTop = true, color = barColor.copy(alpha = if (dim) 0.45f else 1f))
            }
            drawLabel(measurer, day.date.dayOfMonth.toString(), cx, baseline + 4.dp.toPx(), if (isSel) ink else muted, 11)
            drawLabel(
                measurer,
                day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                cx, baseline + 16.dp.toPx(), muted.copy(alpha = 0.7f), 9,
            )
            if (isSel && day.hasData) {
                val h = (day.usedPct / peak) * plotH
                drawLabel(measurer, "${day.usedPct.roundToInt()}%", cx, baseline - h - 16.dp.toPx(), ink, 11)
            }
        }
    }
}

/**
 * Charge and drain per hour on one diverging chart: charge rises above the line, drain hangs
 * below it, both to the same scale, so an up bar and a down bar of equal height really are
 * the same number of percentage points.
 */
@Composable
fun HourBarChart(
    hours: List<HourUsage>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    usedColor: Color,
    chargedColor: Color,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.outlineVariant
    val band = MaterialTheme.colorScheme.surfaceContainerHigh

    Canvas(
        modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(hours.size) {
                detectTapGestures { p ->
                    if (hours.isEmpty()) return@detectTapGestures
                    val i = (p.x / (size.width / hours.size)).toInt().coerceIn(0, hours.size - 1)
                    onSelect(i)
                }
            },
    ) {
        if (hours.isEmpty()) return@Canvas
        val labelH = 18.dp.toPx()
        val plotH = size.height - labelH
        val baseline = plotH * UP_SHARE
        val upH = baseline - 14.dp.toPx()
        val downH = plotH - baseline - 14.dp.toPx()
        val maxUsed = hours.maxOf { it.usedPct }
        val maxCharged = hours.maxOf { it.chargedPct }
        val perPoint = minOf(
            upH / maxCharged.coerceAtLeast(MIN_HOUR_SCALE),
            downH / maxUsed.coerceAtLeast(MIN_HOUR_SCALE),
        )
        val slot = size.width / hours.size
        val barW = slot * 0.64f

        selected?.let { s ->
            drawRect(band.copy(alpha = 0.6f), Offset(slot * s, 0f), Size(slot, plotH))
        }
        drawLine(track, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())

        hours.forEachIndexed { i, hour ->
            val cx = slot * i + slot / 2
            val dim = selected != null && selected != i
            val a = if (dim) 0.45f else 1f
            if (!hour.hasData) {
                drawCircle(muted.copy(alpha = 0.3f), 1.5.dp.toPx(), Offset(cx, baseline))
                return@forEachIndexed
            }
            if (hour.chargedPct > 0f) {
                val h = hour.chargedPct * perPoint
                roundedBar(cx - barW / 2, baseline - h, barW, h, roundTop = true, color = chargedColor.copy(alpha = a))
            }
            if (hour.usedPct > 0f) {
                val h = hour.usedPct * perPoint
                roundedBar(cx - barW / 2, baseline, barW, h, roundTop = false, color = usedColor.copy(alpha = a))
            }
        }

        for (h in listOf(0, 6, 12, 18)) {
            drawLabel(measurer, "%02d".format(h), slot * h + slot / 2, plotH + 3.dp.toPx(), muted, 10)
        }
        selected?.let { s ->
            val hour = hours[s]
            if (hour.usedPct > 0f) {
                val y = baseline + hour.usedPct * perPoint + 3.dp.toPx()
                drawLabel(measurer, "-%.1f%%".format(hour.usedPct), slot * s + slot / 2, y, ink, 10)
            }
            if (hour.chargedPct > 0f) {
                val y = baseline - hour.chargedPct * perPoint - 15.dp.toPx()
                drawLabel(measurer, "+%.1f%%".format(hour.chargedPct), slot * s + slot / 2, y, ink, 10)
            }
        }
    }
}

/** A bar with its far end rounded and its baseline end square, per the chart spec. */
private fun DrawScope.roundedBar(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    roundTop: Boolean,
    color: Color,
) {
    if (height <= 0.5f) return
    val r = CornerRadius(minOf(4.dp.toPx(), width / 2, height))
    val rect = if (roundTop) {
        RoundRect(left, top, left + width, top + height, r, r, CornerRadius.Zero, CornerRadius.Zero)
    } else {
        RoundRect(left, top, left + width, top + height, CornerRadius.Zero, CornerRadius.Zero, r, r)
    }
    drawPath(Path().apply { addRoundRect(rect) }, color)
}

/** Centred text at (cx, top). */
private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    cx: Float,
    top: Float,
    color: Color,
    sizeSp: Int,
) {
    val layout = measurer.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontFeatureSettings = "tnum"))
    val x = (cx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
    drawText(layout, topLeft = Offset(x, top))
}

/** Floors for the scales, so one quiet day or hour is not drawn as if it were huge. */
private const val MIN_DAY_SCALE = 20f
private const val MIN_HOUR_SCALE = 4f
private const val UP_SHARE = 0.38f
