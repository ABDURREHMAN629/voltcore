package com.voltcore.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.voltcore.app.core.AppGraph
import com.voltcore.app.core.battery.TelemetryDiagnostics
import com.voltcore.app.service.LevelLogWorker

class VoltCoreApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        createNotificationChannels()

        LevelLogWorker.schedule(this)

        Thread { runCatching { TelemetryDiagnostics(this).log() } }.start()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_monitor_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }

        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_alerts_desc)
            enableVibration(true)
        }

        nm.createNotificationChannels(listOf(monitor, alerts))
    }

    companion object {
        const val CHANNEL_MONITOR = "voltcore.monitor"
        const val CHANNEL_ALERTS = "voltcore.alerts"
    }
}
