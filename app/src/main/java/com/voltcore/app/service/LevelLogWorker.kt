package com.voltcore.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.voltcore.app.VoltCoreApp
import java.util.concurrent.TimeUnit

/**
 * Writes one battery-level reading every 15 minutes, so the day and hour views have data
 * whether or not the live monitor is running.
 *
 * WorkManager rather than a foreground service: 15 minutes is its floor and plenty for
 * hour-level totals, and it lets the system batch this with other apps' work and hold it
 * back during deep sleep. A missed run costs nothing — the next reading still pins down the
 * level, and the hour maths spreads the change across the gap.
 *
 * Once a day it also refreshes the import of Android's own battery history, when that has
 * been permitted. Android only keeps about two weeks, so a daily copy is what lets older
 * days keep their detail.
 */
class LevelLogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = (applicationContext as? VoltCoreApp)?.graph ?: return Result.success()
        runCatching { graph.batteryLog.recordNow() }
        runCatching { graph.batteryLog.importAndroidHistory(minIntervalMs = DAILY_IMPORT_MS) }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "voltcore.level-log"
        private const val DAILY_IMPORT_MS = 20L * 60 * 60 * 1000

        /** Idempotent: KEEP leaves an existing schedule alone, so calling on every launch is fine. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LevelLogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
