package com.voltcore.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val VoltTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Light, fontSize = 57.sp, lineHeight = 60.sp, letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp,
    ),
)

/**
 * Readout styles.
 *
 * Live numbers change several times a second. Without tabular figures every digit change
 * reflows the text and the whole dashboard jitters, so these lock to monospaced digits.
 */
object VoltText {
    private const val TABULAR = "tnum"

    /** The hero number: amps or watts on the dashboard. */
    val hero = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 68.sp,
        lineHeight = 72.sp,
        letterSpacing = (-2).sp,
        fontFeatureSettings = TABULAR,
        textAlign = TextAlign.Center,
    )

    /** Stat-tile values. */
    val readout = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = TABULAR,
    )

    val readoutSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR,
    )

    /** The unit that trails a readout. */
    val unit = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    )

    /** Small-caps-ish caption above a tile. */
    val caption = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp,
    )
}

val VoltShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)
