package com.voltcore.app.core.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.voltcore.app.core.model.BatteryHealth
import com.voltcore.app.core.model.BatterySnapshot
import com.voltcore.app.core.model.ChargeProtocol
import com.voltcore.app.core.model.ChargeStatus
import com.voltcore.app.core.model.PlugType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * The single source of truth for what the battery is doing.
 *
 * Readings are stitched together from three places, in order of preference:
 *  1. the sticky ACTION_BATTERY_CHANGED intent — level, status, voltage, temperature;
 *  2. BatteryManager properties — instantaneous and average current, charge counter;
 *  3. [SysfsProbe] — everything the framework refuses to expose, above all the charger
 *     side of the circuit, which is what turns "1.8 A" into "9 V, 1.8 A, 16 W, USB PD".
 *
 * Current readings pass through [CurrentCalibrator] because sign and units vary by OEM.
 * The framework and sysfs get their own calibrators: some HALs normalise to microamps
 * while the underlying kernel node reports milliamps, so the two are not interchangeable.
 */
class BatteryTelemetry(
    private val context: Context,
    private val sysfs: SysfsProbe = SysfsProbe(),
) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val powerManager: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    val frameworkCalibrator = CurrentCalibrator()
    val sysfsCalibrator = CurrentCalibrator()

    /**
     * Produces a fractional level on devices with no charge counter. Held here so its
     * running total survives across readings.
     */
    val levelEstimator = LevelEstimator()

    /** Fired when a calibrator learns something worth persisting. */
    var onCalibrationChanged: ((CalibrationState) -> Unit)? = null

    // Capacity is a device constant; probing it costs file IO, so cache it.
    private val designCapacity: Float? by lazy {
        sysfs.designCapacityMah() ?: powerProfileCapacityMah()
    }

    /** Takes one full reading. Safe to call from any thread; does blocking file IO. */
    fun read(): BatterySnapshot {
        val intent = stickyBatteryIntent()

        val level = intent?.let {
            val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) (raw * 100f / scale).toInt() else null
        } ?: batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 0

        val status = ChargeStatus.fromAndroid(
            intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
        )
        val plug = PlugType.fromAndroid(intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
        val charging = status.isCharging

        val now = System.currentTimeMillis()
        val currentMa = readCurrentMa(charging)
        val chargeCounter = readChargeCounterMah()

        // Present full-charge capacity, which degrades with age. Stays null when the
        // device will not report it — substituting the design figure here would make
        // health read a flat 100% forever.
        val measuredFullCapacity = sysfs.fullCapacityMah()

        // For sizing the battery, the factory rating is a fine stand-in.
        val capacityForEstimate = measuredFullCapacity ?: designCapacity

        // Seed the estimator with it. Without this it cannot say anything until it has
        // watched a whole percent tick by, which can be twenty minutes; with it, decimals
        // are available immediately and the learned figure refines the seed later.
        capacityForEstimate?.let { levelEstimator.seedCapacity(it) }

        // Prefer a real fractional figure. Fall back to coulomb counting only when the
        // device gives us nothing better, and flag it so the UI can say which it is.
        val measuredLevel = preciseLevel(intent, chargeCounter, measuredFullCapacity, level)
        val estimatedLevel = if (measuredLevel == null) {
            levelEstimator.observe(level, currentMa, now)
        } else {
            null
        }

        return BatterySnapshot(
            timestampMs = now,
            levelPercent = level,
            levelPrecise = measuredLevel ?: estimatedLevel,
            levelIsEstimated = measuredLevel == null && estimatedLevel != null,
            status = status,
            plug = plug,
            health = BatteryHealth.fromAndroid(
                intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0,
            ),
            technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            temperatureC = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE && it != 0 }
                ?.let { it / 10f }
                ?: sysfs.temperatureC(),
            voltageV = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it > 0 }
                ?.let { mv -> if (mv > 100_000) mv / 1_000_000f else mv / 1000f }
                ?: sysfs.batteryVoltageV(),
            currentMa = currentMa,
            avgCurrentMa = readAverageCurrentMa(),
            // Reuse the values computed above rather than re-reading them: each of these
            // is a binder call or a file open, and this runs once a second.
            chargeCounterMah = chargeCounter,
            fullCapacityMah = measuredFullCapacity,
            designCapacityMah = designCapacity,
            cycleCount = readCycleCount(intent),
            stateOfHealthPercent = sysfs.stateOfHealthPercent(),
            chargerVoltageV = sysfs.chargerVoltageV(),
            chargerCurrentMa = sysfs.chargerCurrentMa(),
            protocol = resolveProtocol(plug),
            screenOn = powerManager?.isInteractive ?: true,
        )
    }

    /**
     * Emits a reading every [intervalMs]. Cold; each collector gets its own loop.
     *
     * Pinned to the IO dispatcher: [read] opens files and registers a receiver, and doing
     * that on the main thread once a second cost frames on every screen that collects it.
     */
    fun stream(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<BatterySnapshot> = flow {
        while (true) {
            emit(read())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Milliseconds until full, or null when the platform cannot say.
     * Only meaningful while charging.
     */
    fun chargeTimeRemainingMs(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val raw = runCatching { batteryManager?.computeChargeTimeRemaining() }.getOrNull() ?: return null
        return raw.takeIf { it > 0L }
    }

    // ------------------------------------------------------------------ current

    private fun readCurrentMa(charging: Boolean): Float? {
        frameworkRawCurrent()?.let { raw ->
            if (frameworkCalibrator.observe(raw, charging)) {
                onCalibrationChanged?.invoke(frameworkCalibrator.state)
            }
            return frameworkCalibrator.toMilliAmps(raw)
        }
        sysfs.rawBatteryCurrent()?.let { raw ->
            sysfsCalibrator.observe(raw, charging)
            return sysfsCalibrator.toMilliAmps(raw)
        }
        return null
    }

    private fun readAverageCurrentMa(): Float? {
        frameworkProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            ?.let { return frameworkCalibrator.toMilliAmps(it) }
        return sysfs.rawAverageCurrent()?.let { sysfsCalibrator.toMilliAmps(it) }
    }

    private fun frameworkRawCurrent(): Long? =
        frameworkProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

    /**
     * Reads a BatteryManager property, filtering the sentinels the platform uses for
     * "not supported". Devices variously return Int.MIN_VALUE, Long.MIN_VALUE or 0.
     */
    private fun frameworkProperty(id: Int): Long? {
        val bm = batteryManager ?: return null
        val value = runCatching { bm.getLongProperty(id) }.getOrNull() ?: return null
        if (value == Long.MIN_VALUE || value == Int.MIN_VALUE.toLong()) return null
        // A property that is genuinely unsupported tends to peg at 0 forever. Zero is a
        // legitimate current though, so only reject it when it is also the int sentinel.
        val asInt = runCatching { bm.getIntProperty(id) }.getOrNull()
        if (value == 0L && (asInt == null || asInt == Int.MIN_VALUE)) return null
        return value
    }

    /**
     * Battery level to one decimal place.
     *
     * Android's level extra is an integer, so a real fractional figure has to come from
     * somewhere with more resolution:
     *
     *  1. the fuel gauge's charge counter over full capacity — genuinely precise, and the
     *     same arithmetic the system itself uses to produce the integer;
     *  2. a device whose battery scale is finer than 100 (a few report 255 or 1000).
     *
     * When neither exists this returns null and the UI falls back to the whole number.
     * Padding an integer with ".0" would claim precision the hardware never provided.
     */
    private fun preciseLevel(
        intent: Intent?,
        chargeCounterMah: Float?,
        fullCapacityMah: Float?,
        fallbackLevel: Int,
    ): Float? {
        if (chargeCounterMah != null && fullCapacityMah != null && fullCapacityMah > 0f) {
            val pct = chargeCounterMah / fullCapacityMah * 100f
            // Guard against a stale or mismatched capacity making this nonsense: it must
            // agree with the integer the framework reports.
            if (pct in 0f..100.5f && kotlin.math.abs(pct - fallbackLevel) <= 2f) {
                return pct.coerceIn(0f, 100f)
            }
        }

        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (raw >= 0 && scale > 100) return (raw * 100f / scale).coerceIn(0f, 100f)

        return null
    }

    private fun readChargeCounterMah(): Float? {
        frameworkProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let { raw ->
            // Documented as microamp-hours.
            val mah = if (kotlin.math.abs(raw) > 100_000L) raw / 1000f else raw.toFloat()
            if (mah > 0f) return mah
        }
        return sysfs.chargeCounterMah()
    }

    private fun readCycleCount(intent: Intent?): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                ?.takeIf { it in 1..5000 }
                ?.let { return it }
        }
        return sysfs.cycleCount()
    }

    // ------------------------------------------------------------------ misc

    private fun resolveProtocol(plug: PlugType): ChargeProtocol {
        val fromKernel = sysfs.chargeProtocol()
        if (fromKernel != ChargeProtocol.UNKNOWN) return fromKernel
        // Fall back to what the plug type implies.
        return when (plug) {
            PlugType.WIRELESS -> ChargeProtocol.WIRELESS
            PlugType.AC -> ChargeProtocol.DCP
            PlugType.USB -> ChargeProtocol.SDP
            else -> ChargeProtocol.UNKNOWN
        }
    }

    private fun stickyBatteryIntent(): Intent? = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()

    /**
     * Last-resort design capacity, read from the framework's own power profile.
     * Blocked by non-SDK interface restrictions on most modern builds, so this is
     * genuinely a fallback for when sysfs is unreadable.
     */
    private fun powerProfileCapacityMah(): Float? = runCatching {
        val cls = Class.forName("com.android.internal.os.PowerProfile")
        val instance = cls.getConstructor(Context::class.java).newInstance(context)
        val value = cls.getMethod("getBatteryCapacity").invoke(instance) as? Double
        value?.toFloat()?.takeIf { it > 100f }
    }.getOrNull()

    companion object {
        const val DEFAULT_INTERVAL_MS = 1000L
        const val FAST_INTERVAL_MS = 500L
    }
}
