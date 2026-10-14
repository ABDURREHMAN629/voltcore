package com.voltcore.app.core.alerts

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import com.voltcore.app.MainActivity
import com.voltcore.app.R
import com.voltcore.app.VoltCoreApp
import com.voltcore.app.core.data.VoltPrefs
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.core.model.BatterySnapshot
import com.voltcore.app.core.util.Fmt

/**
 * Battery alerts.
 *
 * Everything here is edge-triggered and latched: an alert fires when a threshold is first
 * crossed and does not fire again until the condition clears. A level-triggered version
 * would re-notify on every sample, and a notification that appears once a second is a
 * notification the user turns off.
 */
class AlertManager(
    private val context: Context,
    private val prefs: VoltPrefs,
) {

    private var fullNotified = false
    private var limitNotified = false
    private var overheatNotified = false
    private var lowNotified = false

    /** Feeds one reading. Cheap; call from the monitor loop. */
    fun evaluate(snapshot: BatterySnapshot, settings: VoltSettings) {
        val charging = snapshot.status.isCharging

        if (settings.alertOnFull) {
            val full = snapshot.levelPercent >= 100 ||
                snapshot.status == com.voltcore.app.core.model.ChargeStatus.FULL
            if (full && charging && !fullNotified) {
                fullNotified = true
                notify(
                    id = ID_FULL,
                    title = "Battery full",
                    body = "Charged to 100%. Unplugging now avoids holding the cell at " +
                        "full voltage, which is what actually ages it.",
                    urgent = true,
                )
            }
            if (!full) fullNotified = false
        }

        if (settings.chargeLimitEnabled) {
            val hit = snapshot.levelPercent >= settings.chargeLimitPercent
            if (hit && charging && !limitNotified) {
                limitNotified = true
                notify(
                    id = ID_LIMIT,
                    title = "Reached ${settings.chargeLimitPercent}%",
                    body = "Time to unplug. VoltCore cannot stop the charger itself — " +
                        "Android gives no app that control — so this is your cue.",
                    urgent = true,
                )
            }
            if (!hit || !charging) limitNotified = false
        }

        if (settings.alertOnOverheat) {
            val temp = snapshot.temperatureC
            val hot = temp != null && temp >= settings.overheatThresholdC
            if (hot && !overheatNotified) {
                overheatNotified = true
                notify(
                    id = ID_HEAT,
                    title = "Battery is hot: ${Fmt.temperature(temp)} °C",
                    body = "Take the case off, stop charging, or move it out of the sun. " +
                        "Heat degrades a battery faster than charge cycles do.",
                    urgent = true,
                )
            }
            if (temp != null && temp < settings.overheatThresholdC - HEAT_HYSTERESIS_C) {
                overheatNotified = false
            }
        }

        if (settings.alertOnLowBattery) {
            val low = !charging && snapshot.levelPercent <= settings.lowBatteryPercent
            if (low && !lowNotified) {
                lowNotified = true
                notify(
                    id = ID_LOW,
                    title = "Battery at ${snapshot.levelPercent}%",
                    body = "Time to charge.",
                    urgent = false,
                )
            }
            if (charging || snapshot.levelPercent > settings.lowBatteryPercent + 3) {
                lowNotified = false
            }
        }
    }

    private fun notify(id: Int, title: String, body: String, urgent: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = Notification.Builder(context, VoltCoreApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        runCatching { nm.notify(id, notification) }
        if (urgent) vibrate()
    }

    private fun vibrate() = runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Clears latches, e.g. when the user changes a threshold. */
    fun reset() {
        fullNotified = false
        limitNotified = false
        overheatNotified = false
        lowNotified = false
    }

    private companion object {
        const val ID_FULL = 2001
        const val ID_LIMIT = 2002
        const val ID_HEAT = 2003
        const val ID_LOW = 2004
        const val HEAT_HYSTERESIS_C = 3
    }
}
