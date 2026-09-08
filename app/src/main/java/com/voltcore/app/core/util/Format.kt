package com.voltcore.app.core.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Number formatting for the readouts.
 *
 * Every helper returns null for a null input so the UI can distinguish "this device does
 * not report it" from "it is zero" — showing 0 mA for an unreadable sensor is a lie.
 */
object Fmt {

    private val L: Locale get() = Locale.US

    /** Milliamps. Switches to amps above 1 A because six digits is unreadable at a glance. */
    fun current(ma: Float?, showSign: Boolean = false): String? {
        if (ma == null) return null
        val sign = if (showSign && ma > 0f) "+" else ""
        return if (abs(ma) >= 1000f) {
            String.format(L, "%s%.2f", sign, ma / 1000f)
        } else {
            String.format(L, "%s%d", sign, ma.roundToInt())
        }
    }

    fun currentUnit(ma: Float?): String = if (ma != null && abs(ma) >= 1000f) "A" else "mA"

    fun watts(w: Float?): String? = when {
        w == null -> null
        abs(w) >= 10f -> String.format(L, "%.1f", w)
        else -> String.format(L, "%.2f", w)
    }

    fun volts(v: Float?): String? = v?.let { String.format(L, "%.3f", it) }

    fun temperature(celsius: Float?, useFahrenheit: Boolean = false): String? {
        if (celsius == null) return null
        val value = if (useFahrenheit) celsius * 9f / 5f + 32f else celsius
        return String.format(L, "%.1f", value)
    }

    fun temperatureUnit(useFahrenheit: Boolean = false): String = if (useFahrenheit) "°F" else "°C"

    fun mah(value: Float?): String? = value?.let { String.format(L, "%,d", it.roundToInt()) }

    fun percent(value: Int?): String? = value?.toString()

    /**
     * Battery level, with a decimal place when the device actually gives us one.
     * Falls back to the whole number rather than inventing a ".0".
     */
    fun level(precise: Float?, whole: Int): String =
        if (precise != null) String.format(L, "%.1f", precise) else whole.toString()

    /** "1h 24m", "24m", "48s" — never "0h 0m". */
    fun duration(millis: Long?): String? {
        if (millis == null || millis <= 0L) return null
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> String.format(L, "%dh %02dm", hours, minutes)
            minutes > 0 -> String.format(L, "%dm", minutes)
            else -> String.format(L, "%ds", seconds)
        }
    }

    /** Compact clock for chart axes. */
    fun clock(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            L, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }
}
