package com.voltcore.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The dashboard's centrepiece: a circular gauge for battery level whose colour encodes
 * charging speed, with an orbiting comet while current is actually flowing in.
 *
 * The motion is tied to real state — it runs only while charging, and its speed tracks
 * [chargeSpeedFraction] — so a glance tells you something true rather than just looking busy.
 *
 * The animation lives in a separate composable that is only composed when [animate] is
 * true. That matters for battery life: an infinite transition keeps requesting frames for
 * as long as it exists, so merely *ignoring* its value while discharging would still have
 * driven the display at full rate for nothing.
 *
 * @param level        0f..1f battery level.
 * @param accent       colour for the progress arc, chosen from charge speed.
 * @param animate      whether current is flowing in.
 * @param chargeSpeedFraction 0f..1f, drives how fast the comet orbits.
 */
@Composable
fun ChargeRing(
    level: Float,
    accent: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = false,
    chargeSpeedFraction: Float = 0.5f,
    trackColor: Color = Color.White.copy(alpha = 0.07f),
    strokeWidth: Float = 26f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val g = ringGeometry(strokeWidth)
            val sweep = 270f * level.coerceIn(0f, 1f)

            // Static bloom. This used to breathe, which meant allocating a radial
            // gradient shader on every frame — measured at roughly half a CPU core on
            // device. A gradient is expensive to build and cheap to keep, so it is drawn
            // here, once per state change, rather than inside the animation.
            if (animate) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
                        center = g.center,
                        radius = g.radius,
                    ),
                    radius = g.radius,
                    center = g.center,
                )
            }

            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = g.topLeft,
                size = g.arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            if (sweep > 0.5f) {
                // Glow pass: a wide, faint copy of the arc underneath the crisp one.
                drawArc(
                    color = accent.copy(alpha = 0.22f),
                    startAngle = START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = g.topLeft,
                    size = g.arcSize,
                    style = Stroke(width = strokeWidth * 2.1f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.0f to accent.copy(alpha = 0.65f),
                            0.35f to accent,
                            0.75f to accent.copy(alpha = 0.85f),
                            1.0f to accent.copy(alpha = 0.65f),
                        ),
                        center = g.center,
                    ),
                    startAngle = START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = g.topLeft,
                    size = g.arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }

        if (animate) {
            RingMotion(
                level = level,
                accent = accent,
                chargeSpeedFraction = chargeSpeedFraction,
                strokeWidth = strokeWidth,
            )
        }

        content()
    }
}

/**
 * The moving parts. Composed only while charging, so nothing animates otherwise.
 *
 * The comet is drawn **once**, lying along the arc at zero degrees, and then carried round
 * by rotating its layer. Redrawing it at its new position each frame instead — the obvious
 * implementation, and the one this started as — measured at 42.9% of a CPU core on a
 * Redmi, against 3.1% with the animation off. The cost was never the eleven circles; it
 * was invalidating a 620-pixel-square layer sixty times a second. Rotating an already
 * rasterised layer is a GPU transform and costs practically nothing.
 *
 * Reading the animated value inside `graphicsLayer` rather than in the draw lambda is what
 * makes that work: it updates the render node without triggering recomposition or redraw.
 */
@Composable
private fun RingMotion(
    level: Float,
    accent: Color,
    chargeSpeedFraction: Float,
    strokeWidth: Float,
) {
    val transition = rememberInfiniteTransition(label = "ring")

    // One full orbit takes between 3.2s (trickle) and 0.9s (ultra).
    val orbitDurationMs = (3200 - (chargeSpeedFraction.coerceIn(0f, 1f) * 2300)).toInt()
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(orbitDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "orbit",
    )

    val sweep = 270f * level.coerceIn(0f, 1f)
    if (sweep <= TAIL_SPAN_DEG + 4f) return

    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Begin a tail-length in, so the tail never trails off the start of the
                // arc into the ring's open bottom quadrant.
                rotationZ = START_ANGLE + TAIL_SPAN_DEG +
                    orbit * (sweep - TAIL_SPAN_DEG)
                transformOrigin = TransformOrigin.Center
            },
    ) {
        val g = ringGeometry(strokeWidth)
        drawComet(
            center = g.center,
            radius = g.radius,
            accent = accent,
            strokeWidth = strokeWidth,
        )
    }
}

private class RingGeometry(
    val center: Offset,
    val radius: Float,
    val topLeft: Offset,
    val arcSize: Size,
)

/**
 * The arc is always a circle sized from the width and anchored to the top of the box.
 * The gap in a 270-degree ring sits at the bottom, so a caller can give this a container
 * shorter than it is wide and reclaim that dead space without it turning into an ellipse.
 */
private fun DrawScope.ringGeometry(strokeWidth: Float): RingGeometry {
    val inset = strokeWidth * 1.6f
    val diameter = size.width - inset * 2
    val radius = diameter / 2f
    return RingGeometry(
        center = Offset(size.width / 2f, inset + radius),
        radius = radius,
        topLeft = Offset(inset, inset),
        arcSize = Size(diameter, diameter),
    )
}

/**
 * A bright head with a fading tail, drawn at zero degrees — that is, lying along the arc
 * to the right of centre. The caller rotates the whole layer to move it.
 */
private fun DrawScope.drawComet(
    center: Offset,
    radius: Float,
    accent: Color,
    strokeWidth: Float,
) {
    repeat(TAIL_SEGMENTS) { i ->
        val t = i / TAIL_SEGMENTS.toFloat()
        val a = Math.toRadians(-(i * TAIL_STEP_DEG).toDouble())
        drawCircle(
            color = accent.copy(alpha = (1f - t) * 0.75f),
            radius = (strokeWidth / 2.4f) * (1f - t * 0.65f),
            center = Offset(
                x = center.x + radius * cos(a).toFloat(),
                y = center.y + radius * sin(a).toFloat(),
            ),
        )
    }
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = strokeWidth / 3.4f,
        center = Offset(x = center.x + radius, y = center.y),
    )
}

private const val START_ANGLE = 135f
private const val TAIL_SEGMENTS = 10
private const val TAIL_STEP_DEG = 3f
private const val TAIL_SPAN_DEG = TAIL_SEGMENTS * TAIL_STEP_DEG
