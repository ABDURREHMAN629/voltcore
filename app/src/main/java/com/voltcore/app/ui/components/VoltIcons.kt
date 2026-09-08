package com.voltcore.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-built icons.
 *
 * material-icons-extended is a very large artifact for the handful of glyphs this app
 * needs, and none of its stock shapes say "charging current" the way a purpose-drawn
 * bolt and waveform do. These are defined once and cached.
 */
object VoltIcons {

    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathBuilder = block)
        }.build()

    private fun stroked(name: String, width: Float = 2f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Charging bolt — the app's signature mark. */
    val Bolt: ImageVector by lazy {
        filled("Bolt") {
            moveTo(13.5f, 2f)
            lineTo(4.6f, 13.1f)
            curveTo(4.2f, 13.6f, 4.6f, 14.4f, 5.2f, 14.4f)
            lineTo(10.2f, 14.4f)
            lineTo(9.4f, 21.3f)
            curveTo(9.3f, 22.1f, 10.3f, 22.5f, 10.8f, 21.9f)
            lineTo(19.7f, 10.8f)
            curveTo(20.1f, 10.3f, 19.7f, 9.5f, 19.1f, 9.5f)
            lineTo(14.1f, 9.5f)
            lineTo(14.9f, 2.6f)
            curveTo(15f, 1.8f, 14f, 1.4f, 13.5f, 2f)
            close()
        }
    }

    /** Waveform, for the history/graph tab. */
    val Pulse: ImageVector by lazy {
        stroked("Pulse") {
            moveTo(2f, 14f)
            lineTo(6f, 14f)
            lineTo(9f, 5f)
            lineTo(13f, 19f)
            lineTo(16f, 11f)
            lineTo(18f, 14f)
            lineTo(22f, 14f)
        }
    }

    /** Upward chevrons, for boost. */
    val Boost: ImageVector by lazy {
        stroked("Boost") {
            moveTo(5f, 12.5f)
            lineTo(12f, 5.5f)
            lineTo(19f, 12.5f)
            moveTo(5f, 19f)
            lineTo(12f, 12f)
            lineTo(19f, 19f)
        }
    }

    /** Four rounded tiles, for the app list. */
    val Grid: ImageVector by lazy {
        stroked("Grid") {
            moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
            moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
        }
    }

    /** Sliders, for settings. */
    val Sliders: ImageVector by lazy {
        stroked("Sliders") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(4f, 17f); lineTo(20f, 17f)
            moveTo(9f, 4.5f); lineTo(9f, 9.5f)
            moveTo(16f, 14.5f); lineTo(16f, 19.5f)
        }
    }

    /** Battery outline with a terminal. */
    val Battery: ImageVector by lazy {
        stroked("Battery") {
            moveTo(3f, 8f); lineTo(17f, 8f); lineTo(17f, 16f); lineTo(3f, 16f); close()
            moveTo(19.5f, 11f); lineTo(19.5f, 13f)
        }
    }

    /** Thermometer, for temperature callouts. */
    val Thermometer: ImageVector by lazy {
        stroked("Thermometer") {
            moveTo(12f, 3.5f)
            curveTo(10.9f, 3.5f, 10f, 4.4f, 10f, 5.5f)
            lineTo(10f, 13.2f)
            curveTo(8.8f, 14f, 8f, 15.4f, 8f, 17f)
            curveTo(8f, 19.2f, 9.8f, 21f, 12f, 21f)
            curveTo(14.2f, 21f, 16f, 19.2f, 16f, 17f)
            curveTo(16f, 15.4f, 15.2f, 14f, 14f, 13.2f)
            lineTo(14f, 5.5f)
            curveTo(14f, 4.4f, 13.1f, 3.5f, 12f, 3.5f)
            close()
        }
    }

    /** Wall plug, for the charger card. */
    val Plug: ImageVector by lazy {
        stroked("Plug") {
            moveTo(9f, 2.5f); lineTo(9f, 7f)
            moveTo(15f, 2.5f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(18f, 7f); lineTo(18f, 11f)
            curveTo(18f, 14.3f, 15.3f, 17f, 12f, 17f)
            curveTo(8.7f, 17f, 6f, 14.3f, 6f, 11f)
            close()
            moveTo(12f, 17f); lineTo(12f, 21.5f)
        }
    }

    /** Shield, used for the privilege/permission cards. */
    val Shield: ImageVector by lazy {
        stroked("Shield") {
            moveTo(12f, 2.5f)
            lineTo(20f, 5.5f)
            lineTo(20f, 11.5f)
            curveTo(20f, 16.5f, 16.5f, 20f, 12f, 21.5f)
            curveTo(7.5f, 20f, 4f, 16.5f, 4f, 11.5f)
            lineTo(4f, 5.5f)
            close()
        }
    }

    /** Snowflake, for the freezer. */
    val Snowflake: ImageVector by lazy {
        stroked("Snowflake", width = 1.8f) {
            moveTo(12f, 2.5f); lineTo(12f, 21.5f)
            moveTo(3.8f, 7.2f); lineTo(20.2f, 16.8f)
            moveTo(20.2f, 7.2f); lineTo(3.8f, 16.8f)
            moveTo(9.5f, 4.6f); lineTo(12f, 6.6f); lineTo(14.5f, 4.6f)
            moveTo(9.5f, 19.4f); lineTo(12f, 17.4f); lineTo(14.5f, 19.4f)
        }
    }
}
