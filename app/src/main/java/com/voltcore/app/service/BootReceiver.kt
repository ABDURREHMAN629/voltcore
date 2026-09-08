package com.voltcore.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores the live monitor after a reboot or an app update, if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Settings are read inside the service; starting it is cheap and it
                // stops itself when the user has the monitor turned off.
                runCatching { MonitorService.start(context) }
            }
        }
    }
}
