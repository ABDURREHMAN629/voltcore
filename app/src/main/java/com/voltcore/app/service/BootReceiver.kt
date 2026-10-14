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
                runCatching { MonitorService.start(context) }
            }
        }
    }
}
