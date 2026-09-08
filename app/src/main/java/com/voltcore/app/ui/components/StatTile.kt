package com.voltcore.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import com.voltcore.app.ui.theme.VoltText

/**
 * One labelled measurement.
 *
 * [value] is rendered in tabular figures so a changing digit does not shift the layout,
 * and [unit] is a separate, smaller run so the number stays the thing you read.
 * A null [value] renders an em dash: the reading is genuinely unavailable on this device,
 * which is different from it being zero.
 */
@Composable
fun StatTile(
    label: String,
    value: String?,
    unit: String?,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val animatedAccent by animateColorAsState(
        targetValue = accent ?: MaterialTheme.colorScheme.onSurface,
        label = "tileAccent",
    )

    GlassCard(modifier = modifier, cornerRadius = 18.dp, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (accent != null) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(animatedAccent),
                )
                Spacer(Modifier.width(7.dp))
            }
            SectionLabel(label, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value ?: EM_DASH,
                style = VoltText.readout,
                color = if (value == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                } else {
                    animatedAccent
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value != null && !unit.isNullOrBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = VoltText.unit,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

/** Small pill used for protocol / status badges. */
@Composable
fun Badge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = VoltText.caption, color = color)
    }
}

/** A labelled row inside a card: label on the left, value right-aligned. */
@Composable
fun DetailRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: EM_DASH,
            style = VoltText.readoutSmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

const val EM_DASH = "—"
