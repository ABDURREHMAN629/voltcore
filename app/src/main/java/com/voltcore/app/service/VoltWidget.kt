package com.voltcore.app.service

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.voltcore.app.VoltCoreApp
import com.voltcore.app.core.battery.BatteryTelemetry
import com.voltcore.app.core.util.Fmt

/** Home-screen readout: current draw and level, refreshed when the host asks. */
class VoltWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Share the app's telemetry instance rather than making a new one: the current
        // sign and unit convention is *learned* per device, and a private instance would
        // start uncalibrated and could show -1500 mA next to the app's +1500 mA.
        val telemetry = (context.applicationContext as? VoltCoreApp)?.graph?.telemetry
            ?: BatteryTelemetry(context)
        val snapshot = telemetry.read()

        provideContent {
            val current = Fmt.current(snapshot.currentMa, showSign = true)
            val headline = if (current != null) {
                "$current ${Fmt.currentUnit(snapshot.currentMa)}"
            } else {
                "${snapshot.levelPercent}%"
            }
            val detail = listOfNotNull(
                "${snapshot.levelPercent}%",
                Fmt.watts(snapshot.batteryPowerW)?.let { "$it W" },
                Fmt.temperature(snapshot.temperatureC)?.let { "$it °C" },
            ).joinToString("  ·  ")

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFF0A1020))
                    .cornerRadius(20.dp)
                    .padding(14.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.Start,
            ) {
                Text(
                    text = headline,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF22D3EE)),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = detail,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF9FB0C9)),
                        fontSize = 12.sp,
                    ),
                )
            }
        }
    }
}

class VoltWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoltWidget()
}
