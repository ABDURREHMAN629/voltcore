package com.voltcore.app.core

import android.content.Context
import com.voltcore.app.core.battery.BatteryTelemetry
import com.voltcore.app.core.battery.SysfsProbe
import com.voltcore.app.core.alerts.AlertManager
import com.voltcore.app.core.boost.BoostEngine
import com.voltcore.app.core.data.AppUsageRepository
import com.voltcore.app.core.data.BatteryLogRepository
import com.voltcore.app.core.data.HistoryRepository
import com.voltcore.app.core.data.InstalledAppsRepository
import com.voltcore.app.core.data.ScreenTimeRepository
import com.voltcore.app.core.data.VoltPrefs
import com.voltcore.app.core.privileged.LocalDumpsys
import com.voltcore.app.core.privileged.PrivilegeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency graph.
 *
 * A single-module app with this few collaborators does not earn the build cost and the
 * version coupling of a DI framework. Everything is lazy, so nothing touches the
 * filesystem until it is first used.
 */
class AppGraph(context: Context) {

    val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: VoltPrefs by lazy { VoltPrefs(appContext) }

    val sysfs: SysfsProbe by lazy { SysfsProbe() }

    val telemetry: BatteryTelemetry by lazy {
        BatteryTelemetry(appContext, sysfs).also { engine ->
            engine.onCalibrationChanged = { state ->
                scope.launch { prefs.saveCalibration(state) }
            }
            // Follow the setting rather than reading it once: a manual override chosen in
            // Settings has to take effect on the next reading, not the next app launch.
            scope.launch {
                prefs.settings
                    .map { it.calibrationMode }
                    .distinctUntilChanged()
                    .collect { mode ->
                        engine.frameworkCalibrator.mode = mode
                        engine.sysfsCalibrator.mode = mode
                    }
            }

            // The level estimator needs a capacity. Prefer the user's figure, fall back to
            // whatever it worked out on a previous run so decimals are available straight
            // away rather than only after the next whole-percent tick.
            engine.levelEstimator.onCapacityLearned = { mah ->
                scope.launch { prefs.setLearnedCapacity(mah) }
            }
            scope.launch {
                prefs.settings
                    .map { it.batteryCapacityMah to it.learnedCapacityMah }
                    .distinctUntilChanged()
                    .collect { (userMah, learnedMah) ->
                        when {
                            userMah > 0 -> engine.levelEstimator.setUserCapacity(userMah.toFloat())
                            learnedMah > 0f -> engine.levelEstimator.setUserCapacity(learnedMah)
                        }
                    }
            }
        }
    }

    val privilege: PrivilegeManager by lazy { PrivilegeManager(appContext) }

    val installedApps: InstalledAppsRepository by lazy { InstalledAppsRepository(appContext) }

    val history: HistoryRepository by lazy { HistoryRepository(appContext) }

    /** Runs dumpsys in-process once `DUMP` has been granted over USB; no Shizuku needed. */
    val dumpsys: LocalDumpsys by lazy { LocalDumpsys(appContext) }

    val appUsage: AppUsageRepository by lazy { AppUsageRepository(appContext, privilege, dumpsys) }

    val batteryLog: BatteryLogRepository by lazy { BatteryLogRepository(appContext, dumpsys) }

    val screenTime: ScreenTimeRepository by lazy { ScreenTimeRepository(appContext) }

    val boost: BoostEngine by lazy { BoostEngine(telemetry, privilege, prefs) }

    val alerts: AlertManager by lazy { AlertManager(appContext, prefs) }
}
