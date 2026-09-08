package com.voltcore.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles taps on action buttons attached to VoltCore notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_MONITOR -> runCatching { MonitorService.stop(context) }
        }
    }

    companion object {
        const val ACTION_STOP_MONITOR = "com.voltcore.app.action.STOP_MONITOR"
    }
}
