package com.voltcore.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voltcore.app.ui.theme.VoltText
import kotlin.math.abs

/** One plotted point. */
data class ChartPoint(val x: Long, val y: Float)

/**
 * A single-measure time series.
 *
 * Deliberately one measure per chart. Current and temperature share a time axis but
 * nothing else, and putting them on one plot would need a second y-scale — which makes
 * the crossing point of the two lines look meaningful when it is an artefact of whatever
 * scales were chosen. Two stacked charts say the same thing without the illusion.
 *
 * One series means no legend is needed; the caption names it. Grid and axes stay
 * recessive, and only the extremes are labelled rather than every point.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    accent: Color,
    modifier: Modifier = Modifier,
    height: Dp = 130.dp,
    /** Force the y-axis to include zero — right for signed values like current. */
    includeZero: Boolean = false,
    valueFormatter: (Float) -> String = { it.toString() },
) {
    if (points.size < 2) {
        EmptyChart(modifier.height(height))
        return
    }

    var selected by remember { mutableStateOf<Int?>(null) }

    val minX = points.first().x
    val maxX = points.last().x
    val spanX = (maxX - minX).coerceAtLeast(1L).toFloat()

    val rawMin = points.minOf { it.y }
    val rawMax = points.maxOf { it.y }
    val lo = if (includeZero) minOf(rawMin, 0f) else rawMin
    val hi = if (includeZero) maxOf(rawMax, 0f) else rawMax
    // Pad so the line never touches the frame, and never divide by zero on a flat series.
    val pad = ((hi - lo) * 0.12f).takeIf { it > 0.0001f } ?: (abs(hi).coerceAtLeast(1f) * 0.2f)
    val yMin = lo - pad
    val yMax = hi + pad
    val spanY = (yMax - yMin).coerceAtLeast(0.0001f)

    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        fun px(p: ChartPoint) = Offset(
            x = (p.x - minX) / spanX * w,
            y = h - ((p.y - yMin) / spanY) * h,
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures(
                        onPress = { offset ->
                            selected = nearestIndex(points, offset.x, minX, spanX, w)
                            tryAwaitRelease()
                            selected = null
                        },
                    )
                }
                .pointerInput(points) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { selected = null },
                        onDragCancel = { selected = null },
                    ) { change, _ ->
                        selected = nearestIndex(points, change.position.x, minX, spanX, w)
                    }
                },
        ) {
            // --- Baseline. Only drawn when the data actually crosses zero, where it
            // separates charging from discharging and therefore means something.
            if (yMin < 0f && yMax > 0f) {
                val zeroY = h - ((0f - yMin) / spanY) * h
                drawLine(
                    color = Color.Gray.copy(alpha = 0.35f),
                    start = Offset(0f, zeroY),
                    end = Offset(w, zeroY),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
                )
            }

            val linePath = Path()
            val fillPath = Path()
            points.forEachIndexed { i, p ->
                val o = px(p)
                if (i == 0) {
                    linePath.moveTo(o.x, o.y)
                    fillPath.moveTo(o.x, h)
                    fillPath.lineTo(o.x, o.y)
                } else {
                    linePath.lineTo(o.x, o.y)
                    fillPath.lineTo(o.x, o.y)
                }
            }
            fillPath.lineTo(w, h)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.02f)),
                ),
            )
            drawPath(
                path = linePath,
                color = accent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // --- Crosshair while the user is inspecting.
            selected?.let { idx ->
                val o = px(points[idx])
                drawLine(
                    color = accent.copy(alpha = 0.45f),
                    start = Offset(o.x, 0f),
                    end = Offset(o.x, h),
                    strokeWidth = 1.dp.toPx(),
                )
                // Ring the marker in the surface colour so it stays legible on the fill.
                drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = 6.dp.toPx(), center = o)
                drawCircle(color = accent, radius = 4.dp.toPx(), center = o)
            }
        }

        // --- Selective direct labels: the extremes, plus the inspected value.
        val idx = selected
        if (idx != null) {
            Text(
                text = valueFormatter(points[idx].y),
                style = VoltText.readoutSmall,
                color = accent,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            )
        } else {
            Text(
                text = valueFormatter(rawMax),
                style = VoltText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart),
            )
            Text(
                text = valueFormatter(rawMin),
                style = VoltText.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

private fun nearestIndex(
    points: List<ChartPoint>,
    touchX: Float,
    minX: Long,
    spanX: Float,
    width: Float,
): Int {
    if (width <= 0f) return 0
    val fraction = (touchX / width).coerceIn(0f, 1f)
    val targetX = minX + (fraction * spanX).toLong()
    var best = 0
    var bestDelta = Long.MAX_VALUE
    points.forEachIndexed { i, p ->
        val d = abs(p.x - targetX)
        if (d < bestDelta) {
            bestDelta = d
            best = i
        }
    }
    return best
}

@Composable
private fun EmptyChart(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "Not enough data yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
