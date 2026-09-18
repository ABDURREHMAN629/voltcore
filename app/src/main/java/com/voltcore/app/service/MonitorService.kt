package com.voltcore.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voltcore.app.MainActivity
import com.voltcore.app.R
import com.voltcore.app.VoltCoreApp
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.core.freezer.ScreenStateAutomation
import com.voltcore.app.core.model.BatterySnapshot
import com.voltcore.app.core.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a live readout in the shade, records history, and raises alerts.
 *
 * This has to be a foreground service. The readings are only meaningful at one-second
 * resolution, and any deferrable scheduler would coalesce them into uselessness — you
 * cannot watch a charging curve through WorkManager.
 */
class MonitorService : LifecycleService() {

    private val graph by lazy { (application as VoltCoreApp).graph }
    private var lastPruneMs = 0L
    private var lastWidgetMs = 0L
    private var lastNotificationSignature: String? = null
    private var lastLoggedLevel = -1
    private var lastLoggedPlugged: Boolean? = null

    /**
     * Screen state is only observable from a running process — ACTION_SCREEN_OFF cannot
     * be declared in the manifest — so the automation lives with this service.
     */
    private val screenAutomation by lazy {
        ScreenStateAutomation(
            context = this,
            prefs = graph.prefs,
            privilege = graph.privilege,
            scope = lifecycleScope,
        )
    }

    /**
     * Latest settings, kept in a field rather than re-read per sample. The sample loop
     * runs once a second; collecting the preference flow fresh each tick would spin up a
     * collector a second for values that change perhaps twice a day.
     */
    @Volatile
    private var settings: VoltSettings = VoltSettings()

    override fun onCreate() {
        super.onCreate()
        startInForeground(BatterySnapshot())
        screenAutomation.start()

        lifecycleScope.launch {
            graph.prefs.settings.collect { settings = it }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val initial = graph.prefs.settings.first()
            settings = initial
            if (!initial.monitorEnabled) {
                stopSelf()
                return@launch
            }

            // A hand-rolled loop rather than telemetry.stream(), because the interval has
            // to change as the screen does.
            while (isActive) {
                val snapshot = graph.telemetry.read()
                onSample(snapshot)

                // This single decision is most of the app's own battery cost. With the
                // screen off nobody can read the notification, so sampling every second
                // buys nothing and wakes the CPU 3600 times an hour.
                val interval = if (snapshot.screenOn) {
                    settings.sampleIntervalMs.toLong()
                } else {
                    SCREEN_OFF_INTERVAL_MS
                }
                delay(interval)
            }
        }
    }

    private suspend fun onSample(snapshot: BatterySnapshot) {
        val settings = this.settings

        // Let go once the charger is out, if the user wants it that way.
        if (settings.monitorOnlyWhileCharging && !snapshot.status.isCharging &&
            snapshot.plug == com.voltcore.app.core.model.PlugType.NONE
        ) {
            stopSelf()
            return
        }

        notify(snapshot, settings)
        runCatching { graph.history.record(snapshot) }

        // While the monitor runs it sees every whole-percent step, which pins charging curves
        // far more tightly than the 15-minute background readings. Log only on change.
        val plugged = snapshot.plug != com.voltcore.app.core.model.PlugType.NONE
        if (snapshot.levelPercent != lastLoggedLevel || plugged != lastLoggedPlugged) {
            lastLoggedLevel = snapshot.levelPercent
            lastLoggedPlugged = plugged
            runCatching { graph.batteryLog.record(snapshot.levelPercent, plugged, snapshot.timestampMs) }
        }
        runCatching { graph.alerts.evaluate(snapshot, settings) }

        val now = System.currentTimeMillis()

        // The widget has no update period of its own (updatePeriodMillis=0); it is
        // refreshed from here while the service is alive, at a far coarser cadence than
        // the notification so it does not cost anything noticeable.
        if (now - lastWidgetMs > WIDGET_INTERVAL_MS) {
            lastWidgetMs = now
            runCatching { VoltWidget().updateAll(this) }
        }

        if (now - lastPruneMs > PRUNE_INTERVAL_MS) {
            lastPruneMs = now
            runCatching { graph.history.prune() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        screenAutomation.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startInForeground(snapshot: BatterySnapshot) {
        val notification = buildNotification(snapshot, VoltSettings())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Posts the readout, but only when it would actually look different.
     *
     * Re-posting an identical notification still costs a binder round trip and a shade
     * relayout. Current wobbles constantly, so this does not skip often while the screen
     * is on — but sitting at a steady trickle, or idle with the screen off, it skips
     * nearly every time.
     */
    private fun notify(snapshot: BatterySnapshot, settings: VoltSettings) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val signature = notificationSignature(snapshot, settings)
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(snapshot, settings)) }
    }

    private fun notificationSignature(s: BatterySnapshot, settings: VoltSettings): String =
        buildNotificationTitle(s) + '|' + buildNotificationDetail(s, settings)

    private fun buildNotification(s: BatterySnapshot, settings: VoltSettings): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, NotificationActionReceiver::class.java)
                .setAction(NotificationActionReceiver.ACTION_STOP_MONITOR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, VoltCoreApp.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(buildNotificationTitle(s))
            .setContentText(buildNotificationDetail(s, settings))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            // Explicit cast: Action.Builder is overloaded on int and Icon, and a bare
            // null makes the choice ambiguous.
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?,
                    "Stop",
                    stop,
                ).build(),
            )
            .build()
    }

    private fun buildNotificationTitle(s: BatterySnapshot): String {
        val current = Fmt.current(s.currentMa, showSign = true)
        val level = Fmt.level(s.levelPrecise, s.levelPercent)
        return if (current != null) {
            "$current ${Fmt.currentUnit(s.currentMa)}  ·  $level%"
        } else {
            "$level%"
        }
    }

    private fun buildNotificationDetail(s: BatterySnapshot, settings: VoltSettings): String =
        listOfNotNull(
            Fmt.watts(s.inputPowerW ?: s.batteryPowerW)?.let { "$it W" },
            Fmt.volts(s.voltageV)?.let { "$it V" },
            Fmt.temperature(s.temperatureC, settings.useFahrenheit)
                ?.let { "$it ${Fmt.temperatureUnit(settings.useFahrenheit)}" },
            s.speed.label.takeIf { s.status.isCharging },
        ).joinToString("  ·  ")

    companion object {
        private const val NOTIFICATION_ID = 1001
        /** Sampling period with the screen off, where the readout cannot be seen. */
        private const val SCREEN_OFF_INTERVAL_MS = 30_000L
        private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
        private const val WIDGET_INTERVAL_MS = 120_000L
        const val ACTION_STOP = "com.voltcore.app.STOP_MONITOR"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MonitorService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}
