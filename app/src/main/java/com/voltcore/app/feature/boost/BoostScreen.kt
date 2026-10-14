package com.voltcore.app.feature.boost

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.boost.BoostAction
import com.voltcore.app.core.boost.BoostPhase
import com.voltcore.app.core.boost.BoostState
import com.voltcore.app.core.util.Fmt
import com.voltcore.app.ui.components.GlassCard
import com.voltcore.app.ui.components.SectionLabel
import com.voltcore.app.ui.components.VoltIcons
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun BoostRoute(graph: AppGraph, autoStart: Boolean = false) {
    val scope = rememberCoroutineScope()
    val engine = remember { graph.boost }
    val state by engine.state.collectAsStateWithLifecycle()

    LaunchedEffect(autoStart) {
        if (autoStart && state.phase == BoostPhase.IDLE) engine.boost()
    }

    BoostScreen(
        state = state,
        onBoost = { scope.launch { engine.boost() } },
        onRestore = { scope.launch { engine.restore() } },
        onDismiss = { engine.dismiss() },
    )
}

@Composable
private fun BoostScreen(
    state: BoostState,
    onBoost: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = VoltTheme.colors
    val running = state.phase != BoostPhase.IDLE && state.phase != BoostPhase.DONE

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Boost charging",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Frees up power the phone is spending on itself, then measures what changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(14.dp))
        BoostDial(state = state, running = running)

        Spacer(Modifier.height(16.dp))

        when (state.phase) {
            BoostPhase.IDLE -> {
                Button(
                    onClick = onBoost,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(VoltIcons.Boost, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start boost", fontWeight = FontWeight.SemiBold)
                }
            }

            BoostPhase.DONE -> {
                ResultCard(state)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Done") }
                    TextButton(onClick = onRestore, modifier = Modifier.weight(1f)) {
                        Text("Resume apps")
                    }
                }
            }

            else -> {
                Text(
                    state.message ?: "Working…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }

        if (state.actions.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GlassCard {
                SectionLabel("What VoltCore did")
                Spacer(Modifier.height(8.dp))
                state.actions.forEach { ActionRow(it) }
            }
        }

        Spacer(Modifier.height(14.dp))
        HonestyCard()
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun BoostDial(state: BoostState, running: Boolean) {
    val colors = VoltTheme.colors
    val transition = rememberInfiniteTransition(label = "boost")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
    val progress by animateFloatAsState(state.progress, tween(600), label = "progress")

    val accent = when {
        state.phase == BoostPhase.DONE && (state.gainMa ?: 0f) > 20f -> colors.fast
        state.phase == BoostPhase.DONE -> colors.slow
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.35f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2.6f

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius * 1.9f,
                ),
                radius = radius * 1.9f,
                center = Offset(cx, cy),
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 14f),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = Stroke(width = 14f, cap = StrokeCap.Round),
            )

            if (running) {
                val points = 44
                var previous: Offset? = null
                for (i in 0..points) {
                    val t = i / points.toFloat()
                    val x = cx - radius * 0.72f + (radius * 1.44f * t)
                    val envelope = sin(Math.PI * t).toFloat()
                    val y = cy + sin(phase + t * 9f) * radius * 0.26f * envelope
                    val p = Offset(x, y)
                    previous?.let {
                        drawLine(accent.copy(alpha = 0.85f), it, p, strokeWidth = 4f, cap = StrokeCap.Round)
                    }
                    previous = p
                }
            }
        }

        if (!running) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val gain = state.gainMa
                if (state.phase == BoostPhase.DONE && gain != null) {
                    Text(
                        text = (if (gain > 0) "+" else "") + Fmt.current(gain).orEmpty(),
                        style = VoltText.hero,
                        color = accent,
                    )
                    Text(
                        "mA change",
                        style = VoltText.unit,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        VoltIcons.Boost,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(state: BoostState) {
    val colors = VoltTheme.colors
    val gain = state.gainMa
    val accent = when {
        gain == null -> colors.trickle
        gain > 20f -> colors.fast
        gain < -20f -> colors.warn
        else -> colors.slow
    }

    GlassCard(accent = accent) {
        SectionLabel("Measured result")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricColumn("Before", Fmt.current(state.baselineMa), "mA")
            MetricColumn("After", Fmt.current(state.resultMa), "mA")
            MetricColumn(
                "Change",
                gain?.let { (if (it > 0) "+" else "") + Fmt.current(it) },
                "mA",
                accent,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = verdict(state),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun verdict(state: BoostState): String {
    val gain = state.gainMa
        ?: return "Could not read charging current on this device, so there is nothing to compare."
    val pct = state.gainPercent
    return when {
        gain > 20f && pct != null ->
            "Charging current rose by ${Fmt.current(gain)} mA " +
                "(${String.format(java.util.Locale.US, "%.0f", pct)}%). " +
                "${state.appsFrozen} app(s) stopped competing for power."
        gain > 20f -> "Charging current rose by ${Fmt.current(gain)} mA."
        gain < -20f ->
            "Current actually fell. That usually means the battery is nearly full and " +
                "the charger is tapering on purpose, not that the boost failed."
        else ->
            "No meaningful change. Nothing significant was drawing power in the " +
                "background, which is a good sign rather than a failure."
    }
}

@Composable
private fun MetricColumn(label: String, value: String?, unit: String, accent: Color? = null) {
    Column(horizontalAlignment = Alignment.Start) {
        SectionLabel(label)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value ?: "—",
                style = VoltText.readout,
                color = accent ?: MaterialTheme.colorScheme.onSurface,
            )
            if (value != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    unit,
                    style = VoltText.unit,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun ActionRow(action: BoostAction) {
    val colors = VoltTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(if (action.applied) colors.good else colors.trickle),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                action.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                action.skippedReason ?: action.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Says plainly what boost can and cannot do. Apps in this category routinely imply they
 * make the charger deliver more power, which is not something software can do; being
 * straight about it is the difference between a tool and a placebo.
 */
@Composable
private fun HonestyCard() {
    GlassCard {
        SectionLabel("How this works")
        Spacer(Modifier.height(6.dp))
        Text(
            "No app can raise the current your charger delivers — that is negotiated " +
                "between the charger and the phone's charging chip, and Android exposes " +
                "no way to change it.\n\n" +
                "What VoltCore can do is cut what the phone spends on itself. Every " +
                "milliamp a background app is not using ends up in the battery instead. " +
                "So it measures your real charging current, stops the apps you picked, " +
                "and measures again — including when the answer is no change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
