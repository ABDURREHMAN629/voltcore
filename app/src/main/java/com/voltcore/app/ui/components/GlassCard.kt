package com.voltcore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voltcore.app.ui.theme.VoltText
import com.voltcore.app.ui.theme.VoltTheme

/**
 * The panel every dashboard section sits on.
 *
 * In dark mode this is a translucent white wash with a hairline border, which reads as
 * frosted glass over the near-black ground. In light mode a wash would be invisible, so
 * it falls back to an opaque surface and leans on the border for definition.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    contentPadding: Dp = 16.dp,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = VoltTheme.colors
    val shape = RoundedCornerShape(cornerRadius)

    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (accent != null) {
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = if (colors.isDark) 0.16f else 0.10f),
                            colors.glass,
                        ),
                    )
                } else {
                    Brush.verticalGradient(listOf(colors.glass, colors.glass))
                },
            )
            .border(1.dp, accent?.copy(alpha = 0.28f) ?: colors.glassBorder, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Section heading used above and inside cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text.uppercase(),
        style = VoltText.caption,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
