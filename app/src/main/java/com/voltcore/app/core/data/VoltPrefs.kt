package com.voltcore.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voltcore.app.core.battery.CalibrationMode
import com.voltcore.app.core.battery.CalibrationState
import com.voltcore.app.core.privileged.FreezeMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voltcore")

/** Everything the user can change, plus the calibration the app learns on its own. */
data class VoltSettings(
    // --- monitor ---
    val monitorEnabled: Boolean = true,
    val monitorOnlyWhileCharging: Boolean = true,
    val sampleIntervalMs: Int = 1000,

    // --- display ---
    val useFahrenheit: Boolean = false,
    val dynamicColor: Boolean = false,
    val keepScreenOn: Boolean = false,

    // --- alerts ---
    val alertOnFull: Boolean = true,
    val chargeLimitEnabled: Boolean = false,
    val chargeLimitPercent: Int = 80,
    val alertOnOverheat: Boolean = true,
    val overheatThresholdC: Int = 42,
    val alertOnLowBattery: Boolean = true,
    val lowBatteryPercent: Int = 15,

    // --- freezer ---
    val frozenPackages: Set<String> = emptySet(),
    val freezeMethod: FreezeMethod = FreezeMethod.SUSPEND,
    val freezeOnScreenOff: Boolean = false,
    val freezeDelayMinutes: Int = 5,
    val unfreezeOnUnlock: Boolean = true,

    // --- boost ---
    val boostFreezesApps: Boolean = true,
    val boostEnablesBatterySaver: Boolean = false,
    val boostRestrictsBuckets: Boolean = true,

    // --- learned, not user-facing ---
    val calibration: CalibrationState = CalibrationState(),
    val calibrationMode: CalibrationMode = CalibrationMode.AUTO,
    /** User-entered battery capacity in mAh; 0 means learn it automatically. */
    val batteryCapacityMah: Int = 0,
    /** Capacity worked out by watching whole-percent ticks. */
    val learnedCapacityMah: Float = 0f,
)

/**
 * Preference storage.
 *
 * Deliberately a single flat DataStore rather than a database: these are a few dozen
 * scalars read on every screen, and the whole thing fits in one read.
 */
class VoltPrefs(private val context: Context) {

    val settings: Flow<VoltSettings> = context.dataStore.data.map { p ->
        VoltSettings(
            monitorEnabled = p[MONITOR_ENABLED] ?: true,
            monitorOnlyWhileCharging = p[MONITOR_WHILE_CHARGING] ?: true,
            sampleIntervalMs = p[SAMPLE_INTERVAL] ?: 1000,

            useFahrenheit = p[USE_FAHRENHEIT] ?: false,
            dynamicColor = p[DYNAMIC_COLOR] ?: false,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: false,

            alertOnFull = p[ALERT_FULL] ?: true,
            chargeLimitEnabled = p[LIMIT_ENABLED] ?: false,
            chargeLimitPercent = p[LIMIT_PERCENT] ?: 80,
            alertOnOverheat = p[ALERT_OVERHEAT] ?: true,
            overheatThresholdC = p[OVERHEAT_C] ?: 42,
            alertOnLowBattery = p[ALERT_LOW] ?: true,
            lowBatteryPercent = p[LOW_PERCENT] ?: 15,

            frozenPackages = p[FROZEN_PACKAGES] ?: emptySet(),
            freezeMethod = p[FREEZE_METHOD]?.let { runCatching { FreezeMethod.valueOf(it) }.getOrNull() }
                ?: FreezeMethod.SUSPEND,
            freezeOnScreenOff = p[FREEZE_ON_SCREEN_OFF] ?: false,
            freezeDelayMinutes = p[FREEZE_DELAY] ?: 5,
            unfreezeOnUnlock = p[UNFREEZE_ON_UNLOCK] ?: true,

            boostFreezesApps = p[BOOST_FREEZE] ?: true,
            boostEnablesBatterySaver = p[BOOST_SAVER] ?: false,
            boostRestrictsBuckets = p[BOOST_BUCKETS] ?: true,

            calibration = CalibrationState(
                signFlipped = p[CAL_SIGN_FLIPPED] ?: false,
                milliAmpUnits = p[CAL_MILLIAMP] ?: false,
                confident = p[CAL_CONFIDENT] ?: false,
            ),
            calibrationMode = p[CAL_MODE]?.let { runCatching { CalibrationMode.valueOf(it) }.getOrNull() }
                ?: CalibrationMode.AUTO,
            batteryCapacityMah = p[CAPACITY_MAH] ?: 0,
            learnedCapacityMah = p[LEARNED_CAPACITY] ?: 0f,
        )
    }

    suspend fun setMonitorEnabled(value: Boolean) = putBool(MONITOR_ENABLED, value)
    suspend fun setMonitorOnlyWhileCharging(value: Boolean) = putBool(MONITOR_WHILE_CHARGING, value)
    suspend fun setSampleInterval(ms: Int) = putInt(SAMPLE_INTERVAL, ms.coerceIn(250, 10_000))

    suspend fun setUseFahrenheit(value: Boolean) = putBool(USE_FAHRENHEIT, value)
    suspend fun setDynamicColor(value: Boolean) = putBool(DYNAMIC_COLOR, value)
    suspend fun setKeepScreenOn(value: Boolean) = putBool(KEEP_SCREEN_ON, value)

    suspend fun setAlertOnFull(value: Boolean) = putBool(ALERT_FULL, value)
    suspend fun setChargeLimitEnabled(value: Boolean) = putBool(LIMIT_ENABLED, value)
    suspend fun setChargeLimitPercent(value: Int) = putInt(LIMIT_PERCENT, value.coerceIn(50, 100))
    suspend fun setAlertOnOverheat(value: Boolean) = putBool(ALERT_OVERHEAT, value)
    suspend fun setOverheatThreshold(value: Int) = putInt(OVERHEAT_C, value.coerceIn(35, 60))
    suspend fun setAlertOnLowBattery(value: Boolean) = putBool(ALERT_LOW, value)
    suspend fun setLowBatteryPercent(value: Int) = putInt(LOW_PERCENT, value.coerceIn(5, 50))

    suspend fun setFrozenPackages(packages: Set<String>) =
        context.dataStore.edit { it[FROZEN_PACKAGES] = packages }

    suspend fun toggleFrozenPackage(packageName: String) = context.dataStore.edit { p ->
        val current = p[FROZEN_PACKAGES] ?: emptySet()
        p[FROZEN_PACKAGES] = if (packageName in current) current - packageName else current + packageName
    }

    suspend fun setFreezeMethod(method: FreezeMethod) =
        context.dataStore.edit { it[FREEZE_METHOD] = method.name }

    suspend fun setFreezeOnScreenOff(value: Boolean) = putBool(FREEZE_ON_SCREEN_OFF, value)
    suspend fun setFreezeDelayMinutes(value: Int) = putInt(FREEZE_DELAY, value.coerceIn(0, 120))
    suspend fun setUnfreezeOnUnlock(value: Boolean) = putBool(UNFREEZE_ON_UNLOCK, value)

    suspend fun setBoostFreezesApps(value: Boolean) = putBool(BOOST_FREEZE, value)
    suspend fun setBoostEnablesBatterySaver(value: Boolean) = putBool(BOOST_SAVER, value)
    suspend fun setBoostRestrictsBuckets(value: Boolean) = putBool(BOOST_BUCKETS, value)

    suspend fun saveCalibration(state: CalibrationState) = context.dataStore.edit {
        it[CAL_SIGN_FLIPPED] = state.signFlipped
        it[CAL_MILLIAMP] = state.milliAmpUnits
        it[CAL_CONFIDENT] = state.confident
    }

    suspend fun setCalibrationMode(mode: CalibrationMode) =
        context.dataStore.edit { it[CAL_MODE] = mode.name }

    suspend fun setBatteryCapacity(mah: Int) =
        putInt(CAPACITY_MAH, mah.coerceIn(0, 15_000))

    suspend fun setLearnedCapacity(mah: Float) =
        context.dataStore.edit { it[LEARNED_CAPACITY] = mah }

    private suspend fun putBool(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun putInt(key: Preferences.Key<Int>, value: Int) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val MONITOR_ENABLED = booleanPreferencesKey("monitor_enabled")
        val MONITOR_WHILE_CHARGING = booleanPreferencesKey("monitor_while_charging")
        val SAMPLE_INTERVAL = intPreferencesKey("sample_interval")

        val USE_FAHRENHEIT = booleanPreferencesKey("use_fahrenheit")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")

        val ALERT_FULL = booleanPreferencesKey("alert_full")
        val LIMIT_ENABLED = booleanPreferencesKey("limit_enabled")
        val LIMIT_PERCENT = intPreferencesKey("limit_percent")
        val ALERT_OVERHEAT = booleanPreferencesKey("alert_overheat")
        val OVERHEAT_C = intPreferencesKey("overheat_c")
        val ALERT_LOW = booleanPreferencesKey("alert_low")
        val LOW_PERCENT = intPreferencesKey("low_percent")

        val FROZEN_PACKAGES = stringSetPreferencesKey("frozen_packages")
        val FREEZE_METHOD = stringPreferencesKey("freeze_method")
        val FREEZE_ON_SCREEN_OFF = booleanPreferencesKey("freeze_on_screen_off")
        val FREEZE_DELAY = intPreferencesKey("freeze_delay")
        val UNFREEZE_ON_UNLOCK = booleanPreferencesKey("unfreeze_on_unlock")

        val BOOST_FREEZE = booleanPreferencesKey("boost_freeze")
        val BOOST_SAVER = booleanPreferencesKey("boost_saver")
        val BOOST_BUCKETS = booleanPreferencesKey("boost_buckets")

        val CAL_SIGN_FLIPPED = booleanPreferencesKey("cal_sign_flipped")
        val CAL_MILLIAMP = booleanPreferencesKey("cal_milliamp")
        val CAL_CONFIDENT = booleanPreferencesKey("cal_confident")
        val CAL_MODE = stringPreferencesKey("cal_mode")
        val CAPACITY_MAH = intPreferencesKey("capacity_mah")
        val LEARNED_CAPACITY = floatPreferencesKey("learned_capacity")
    }
}
