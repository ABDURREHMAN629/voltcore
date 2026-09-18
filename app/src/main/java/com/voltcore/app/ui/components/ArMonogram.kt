package com.voltcore.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voltcore.app.ui.theme.Cyan700
import com.voltcore.app.ui.theme.Indigo500

/**
 * The "AR" maker's mark shown beside the app name.
 *
 * The gradient runs through the *darker* end of the brand palette on purpose: white
 * lettering on the bright cyan used elsewhere would sit near 1.8:1 contrast and read as a
 * smudge at this size. Cyan 700 to Indigo 500 keeps it above 4.5:1 across the whole tile.
 */
@Composable
fun ArMonogram(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(Cyan700, Indigo500))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "AR",
            style = TextStyle(
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.4f).sp,
                letterSpacing = (-0.5).sp,
            ),
        )
    }
}
