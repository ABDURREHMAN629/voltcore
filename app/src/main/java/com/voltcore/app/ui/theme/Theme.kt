package com.voltcore.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Cyan400,
    onPrimary = Ink900,
    primaryContainer = Cyan700,
    onPrimaryContainer = Cyan300,
    secondary = Indigo400,
    onSecondary = Ink900,
    secondaryContainer = Indigo700,
    onSecondaryContainer = Indigo300,
    tertiary = Violet400,
    onTertiary = Ink900,
    background = Ink900,
    onBackground = Color(0xFFE6EDF7),
    surface = Ink800,
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Ink700,
    onSurfaceVariant = Color(0xFF9FB0C9),
    surfaceContainer = Ink700,
    surfaceContainerHigh = Ink600,
    surfaceContainerHighest = Ink500,
    outline = Color(0xFF33415C),
    outlineVariant = Color(0xFF1E2A44),
    error = CriticalDark,
    onError = Ink900,
)

private val LightColors = lightColorScheme(
    primary = Cyan700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFF6FD),
    onPrimaryContainer = Color(0xFF06384A),
    secondary = Indigo500,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E3FF),
    onSecondaryContainer = Color(0xFF1E1B54),
    tertiary = Violet500,
    onTertiary = Color.White,
    background = Paper50,
    onBackground = Color(0xFF10192B),
    surface = Color.White,
    onSurface = Color(0xFF10192B),
    surfaceVariant = Paper100,
    onSurfaceVariant = Color(0xFF4A5769),
    surfaceContainer = Paper100,
    surfaceContainerHigh = Paper200,
    surfaceContainerHighest = Paper300,
    outline = Color(0xFFB6C1D2),
    outlineVariant = Paper200,
    error = CriticalLight,
    onError = Color.White,
)

/**
 * Colours that carry meaning rather than decoration, kept out of the Material scheme so
 * dynamic colour cannot repaint them. An "overheating" red has to stay red even when the
 * user's wallpaper is green.
 */
@Immutable
data class VoltColors(
    val trickle: Color = SpeedTrickleDark,
    val slow: Color = SpeedSlowDark,
    val normal: Color = SpeedNormalDark,
    val fast: Color = SpeedFastDark,
    val ultra: Color = SpeedUltraDark,
    val drain: Color = DrainDark,
    val good: Color = GoodDark,
    val warn: Color = WarnDark,
    val danger: Color = CriticalDark,
    val tempCold: Color = TempColdDark,
    val tempMild: Color = TempMildDark,
    val tempWarm: Color = TempWarmDark,
    val tempHot: Color = TempHotDark,
    val isDark: Boolean = true,
) {
    /** Card fill for the glass panels used throughout the dashboard. */
    val glass: Color get() = if (isDark) Color.White.copy(alpha = 0.045f) else Color.White
    val glassBorder: Color get() =
        if (isDark) Color.White.copy(alpha = 0.09f) else Color(0xFF0F172A).copy(alpha = 0.07f)
    val glow: Color get() = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Transparent

    companion object {
        /**
         * Dark mode is a *selected* set of steps, not an inverted copy of the light one:
         * each was validated against its own surface.
         */
        fun of(isDark: Boolean): VoltColors = if (isDark) {
            VoltColors(isDark = true)
        } else {
            VoltColors(
                trickle = SpeedTrickleLight,
                slow = SpeedSlowLight,
                normal = SpeedNormalLight,
                fast = SpeedFastLight,
                ultra = SpeedUltraLight,
                drain = DrainLight,
                good = GoodLight,
                warn = WarnLight,
                danger = CriticalLight,
                tempCold = TempColdLight,
                tempMild = TempMildLight,
                tempWarm = TempWarmLight,
                tempHot = TempHotLight,
                isDark = false,
            )
        }
    }
}

val LocalVoltColors = staticCompositionLocalOf { VoltColors() }

/** Shorthand: `VoltTheme.colors.fast`. */
object VoltTheme {
    val colors: VoltColors
        @Composable get() = LocalVoltColors.current
}

@Composable
fun VoltCoreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You. Off by default: the dashboard's colour ramp is the readout, so a
     * wallpaper-derived palette actively costs information. Offered in Settings.
     */
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalVoltColors provides VoltColors.of(darkTheme)) {
        MaterialTheme(
            colorScheme = scheme,
            typography = VoltTypography,
            shapes = VoltShapes,
            content = content,
        )
    }
}
