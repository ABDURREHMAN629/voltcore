package com.voltcore.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the monitor the moment a charger is plugged in and lets it go when unplugged,
 * so the service is only alive while there is something worth watching.
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> runCatching { MonitorService.start(context) }
            Intent.ACTION_POWER_DISCONNECTED -> runCatching { MonitorService.stop(context) }
        }
    }
}
