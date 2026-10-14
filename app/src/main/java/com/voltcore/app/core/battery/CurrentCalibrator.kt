package com.voltcore.app.core.battery

import kotlin.math.abs

/**
 * How a device's raw current reading maps onto signed milliamps.
 *
 * @param signFlipped   true when the kernel reports negative while charging.
 * @param milliAmpUnits true when the kernel reports mA even though the framework
 *                      documents BATTERY_PROPERTY_CURRENT_NOW as microamps.
 * @param confident     true once enough samples agreed to trust the above.
 */
data class CalibrationState(
    val signFlipped: Boolean = false,
    val milliAmpUnits: Boolean = false,
    val confident: Boolean = false,
) {
    fun apply(raw: Long): Float {
        val scaled = if (milliAmpUnits) raw.toFloat() else raw / 1000f
        return if (signFlipped) -scaled else scaled
    }
}

/** User escape hatch for the rare device the learner cannot figure out. */
enum class CalibrationMode { AUTO, MICRO_AMPS, MICRO_AMPS_INVERTED, MILLI_AMPS, MILLI_AMPS_INVERTED }

/**
 * Learns a device's current-reporting convention.
 *
 * Android documents BATTERY_PROPERTY_CURRENT_NOW as microamps, positive when charging.
 * In practice a meaningful minority of OEM kernels invert the sign, report milliamps, or
 * both — which is why amperage readouts so often show a negative number while a phone is
 * plainly charging. Rather than hard-coding a device blocklist, this watches readings whose
 * true direction is already known from the charging status and settles on a convention.
 *
 * Not thread-safe; the telemetry engine owns one instance and feeds it from a single loop.
 */
class CurrentCalibrator(initial: CalibrationState = CalibrationState()) {

    var state: CalibrationState = initial
        private set

    private var positiveWhileCharging = 0
    private var negativeWhileCharging = 0
    private var smallMagnitudeHits = 0
    private var largeMagnitudeHits = 0

    /** Set by the user in Settings; anything but [CalibrationMode.AUTO] disables learning. */
    var mode: CalibrationMode = CalibrationMode.AUTO
        set(value) {
            field = value
            state = when (value) {
                CalibrationMode.AUTO -> state
                CalibrationMode.MICRO_AMPS -> CalibrationState(false, false, true)
                CalibrationMode.MICRO_AMPS_INVERTED -> CalibrationState(true, false, true)
                CalibrationMode.MILLI_AMPS -> CalibrationState(false, true, true)
                CalibrationMode.MILLI_AMPS_INVERTED -> CalibrationState(true, true, true)
            }
        }

    /**
     * Feeds one raw reading whose true direction is known.
     *
     * @param charging whether the framework says the battery is charging right now.
     * @return true when the calibration changed and should be persisted.
     */
    fun observe(raw: Long, charging: Boolean): Boolean {
        if (mode != CalibrationMode.AUTO) return false
        if (raw == 0L) return false

        val before = state
        val magnitude = abs(raw)

        if (magnitude >= UA_FLOOR) largeMagnitudeHits++ else smallMagnitudeHits++

        if (largeMagnitudeHits + smallMagnitudeHits >= SAMPLES_NEEDED) {
            val looksMilliAmp = smallMagnitudeHits > largeMagnitudeHits
            if (looksMilliAmp != state.milliAmpUnits) {
                state = state.copy(milliAmpUnits = looksMilliAmp)
            }
        }

        if (charging && magnitude > NOISE_FLOOR) {
            if (raw > 0) positiveWhileCharging++ else negativeWhileCharging++

            val total = positiveWhileCharging + negativeWhileCharging
            if (total >= SAMPLES_NEEDED) {
                val flipped = negativeWhileCharging > positiveWhileCharging
                state = state.copy(signFlipped = flipped, confident = true)
            }
        }

        return state != before
    }

    /** Converts a raw kernel reading to signed milliamps: positive charging. */
    fun toMilliAmps(raw: Long): Float = state.apply(raw)

    /** Discards learned values, e.g. after the user changes the mode back to AUTO. */
    fun reset() {
        state = CalibrationState()
        positiveWhileCharging = 0
        negativeWhileCharging = 0
        smallMagnitudeHits = 0
        largeMagnitudeHits = 0
    }

    private companion object {
        /** Below this a "microamp" reading is really milliamps. */
        const val UA_FLOOR = 1_000L
        /** Ignore near-zero readings; their sign is noise. */
        const val NOISE_FLOOR = 500L
        const val SAMPLES_NEEDED = 8
    }
}
