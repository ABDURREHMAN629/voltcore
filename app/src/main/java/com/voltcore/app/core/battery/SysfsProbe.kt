package com.voltcore.app.core.battery

import com.voltcore.app.core.model.ChargeProtocol
import java.io.File
import kotlin.math.abs

/**
 * Reads the kernel power-supply class directly.
 *
 * The public BatteryManager API deliberately hides the charger side of the circuit, so it
 * cannot tell you that your charger negotiated 9 V at 2 A. The kernel does expose that,
 * usually world-readable, under /sys/class/power_supply. Nothing here needs root; every
 * read is best-effort and returns null when the device or its SELinux policy says no.
 */
class SysfsProbe {

    private data class Supply(val dir: File, val type: String)

    private val supplies: List<Supply> by lazy {
        runCatching {
            File(ROOT).listFiles()
                ?.filter { it.isDirectory }
                ?.map { Supply(it, read(it, "type").orEmpty().uppercase()) }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** The fuel gauge. Prefer a node that actually reports a current. */
    private val batteryDir: File? by lazy {
        val candidates = supplies.filter { it.type == "BATTERY" }.map { it.dir } +
            listOfNotNull(
                File(ROOT, "battery").takeIf { it.isDirectory },
                File(ROOT, "bms").takeIf { it.isDirectory },
            )
        candidates.firstOrNull { dir -> CURRENT_KEYS.any { File(dir, it).canRead() } }
            ?: candidates.firstOrNull()
    }

    /** Wall / USB side of the circuit. */
    private val chargerDirs: List<File> by lazy {
        supplies.filter { it.type in CHARGER_TYPES }
            .map { it.dir }
            .ifEmpty { listOfNotNull(File(ROOT, "usb").takeIf { it.isDirectory }) }
    }

    /**
     * True only when a value can actually be read back.
     *
     * An earlier version tested `batteryDir != null`, which was wrong: on devices that
     * deny the contents of /sys/class/power_supply (Xiaomi HyperOS among them) the
     * directory still reports `isDirectory == true` while every file inside is refused.
     * That made the app claim sysfs worked while every reading came back null.
     *
     * Computed once and cached. That matters for battery life as much as correctness:
     * every reader below short-circuits on it, so a device that denies sysfs costs one
     * probe at startup rather than twenty failed syscalls every second forever.
     */
    val isAvailable: Boolean by lazy {
        val dir = batteryDir ?: return@lazy false
        CURRENT_KEYS.any { readUncached(dir, it) != null } ||
            readUncached(dir, "voltage_now") != null ||
            readUncached(dir, "capacity") != null
    }

    /** Charger-side nodes can be readable when the battery ones are not, and vice versa. */
    private val chargerAvailable: Boolean by lazy {
        chargerDirs.any { dir ->
            readUncached(dir, "online") != null || readUncached(dir, "type") != null
        }
    }

    // ------------------------------------------------------------------ battery side

    /** Raw, uncalibrated current. Units and sign are device-specific — see CurrentCalibrator. */
    fun rawBatteryCurrent(): Long? = guarded { batteryDir?.let { firstLong(it, CURRENT_KEYS) } }

    fun rawAverageCurrent(): Long? = guarded {
        batteryDir?.let { firstLong(it, listOf("current_avg", "current_average", "batt_current_avg")) }
    }

    /** Battery terminal voltage, volts. */
    fun batteryVoltageV(): Float? = guarded {
        batteryDir
            ?.let { firstLong(it, listOf("voltage_now", "voltage_avg", "batt_vol")) }
            ?.let { scaleToBase(it) }
    }

    /** Factory capacity, mAh. */
    fun designCapacityMah(): Float? {
        if (!isAvailable) return null
        val dir = batteryDir ?: return null
        firstLong(dir, listOf("charge_full_design"))?.let { return chargeToMah(it) }
        // Some gauges only report energy; convert using the nominal cell voltage.
        val energyUwh = firstLong(dir, listOf("energy_full_design")) ?: return null
        val nominalV = firstLong(dir, listOf("voltage_max_design", "constant_charge_voltage_max"))
            ?.let { scaleToBase(it) } ?: NOMINAL_CELL_V
        if (nominalV <= 0f) return null
        return (energyUwh / 1_000f) / nominalV
    }

    /** Present full-charge capacity, mAh. This is the number that degrades with age. */
    fun fullCapacityMah(): Float? {
        if (!isAvailable) return null
        val dir = batteryDir ?: return null
        firstLong(dir, listOf("charge_full"))?.let { return chargeToMah(it) }
        val energyUwh = firstLong(dir, listOf("energy_full")) ?: return null
        val nominalV = firstLong(dir, listOf("voltage_max_design"))?.let { scaleToBase(it) }
            ?: NOMINAL_CELL_V
        if (nominalV <= 0f) return null
        return (energyUwh / 1_000f) / nominalV
    }

    fun chargeCounterMah(): Float? = guarded {
        batteryDir?.let { firstLong(it, listOf("charge_counter", "charge_now")) }
            ?.let { chargeToMah(it) }
    }

    fun cycleCount(): Int? = guarded {
        batteryDir?.let { firstLong(it, listOf("cycle_count", "battery_cycle")) }
            ?.toInt()?.takeIf { it in 1..5000 }
    }

    /** Some kernels expose state of health directly, as a percentage. */
    fun stateOfHealthPercent(): Int? = guarded {
        batteryDir?.let { firstLong(it, listOf("state_of_health", "health_index", "soh")) }
            ?.toInt()?.takeIf { it in 1..100 }
    }

    fun temperatureC(): Float? = guarded {
        batteryDir?.let { firstLong(it, listOf("temp", "batt_temp", "temperature")) }
            ?.let { raw -> if (abs(raw) > 1000) raw / 100f else raw / 10f }
    }

    // ------------------------------------------------------------------ charger side

    /** Charger output voltage, volts. 5 / 9 / 12 / 15 / 20 V reveal the PD profile. */
    fun chargerVoltageV(): Float? {
        if (!chargerAvailable) return null
        return chargerDirs.firstNotNullOfOrNull { dir ->
            if (!isOnline(dir)) return@firstNotNullOfOrNull null
            firstLong(dir, listOf("voltage_now", "voltage_max", "input_voltage_settled", "voltage_ocv"))
                ?.let { scaleToBase(it) }
                ?.takeIf { it > 1f }
        }
    }

    /** Current drawn from the charger, mA. */
    fun chargerCurrentMa(): Float? {
        if (!chargerAvailable) return null
        return chargerDirs.firstNotNullOfOrNull { dir ->
            if (!isOnline(dir)) return@firstNotNullOfOrNull null
            firstLong(dir, INPUT_CURRENT_KEYS)
                ?.let { scaleToBase(it) * 1000f }
                ?.takeIf { it > 0f }
        }
    }

    /**
     * Reads the negotiated charging protocol.
     *
     * Mainline kernels expose usb_type as a menu with the active entry in square brackets,
     * for example: Unknown SDP DCP CDP C PD [PD_PPS] BrickID. Qualcomm devices instead
     * expose real_type as a bare string such as USB_HVDCP_3.
     */
    fun chargeProtocol(): ChargeProtocol {
        if (!chargerAvailable) return ChargeProtocol.UNKNOWN
        for (dir in chargerDirs) {
            if (!isOnline(dir)) continue

            read(dir, "usb_type")?.let { menu ->
                val open = menu.indexOf(OPEN_BRACKET)
                val close = menu.indexOf(CLOSE_BRACKET)
                if (open in 0 until close) {
                    mapProtocol(menu.substring(open + 1, close))?.let { return it }
                }
            }
            read(dir, "real_type")?.let { raw -> mapProtocol(raw)?.let { return it } }
            read(dir, "type")?.let { raw ->
                if (raw.uppercase().contains("WIRELESS")) return ChargeProtocol.WIRELESS
                mapProtocol(raw)?.let { return it }
            }
            when (readLong(dir, "pd_active")) {
                1L -> return ChargeProtocol.PD
                2L -> return ChargeProtocol.PPS
            }
        }
        return ChargeProtocol.UNKNOWN
    }

    private fun mapProtocol(raw: String): ChargeProtocol? {
        val v = raw.trim().uppercase().replace("USB_", "").replace("-", "_")
        return when {
            v.contains("PPS") -> ChargeProtocol.PPS
            v.contains("PD") -> ChargeProtocol.PD
            v.contains("QC4") || v.contains("HVDCP_4") -> ChargeProtocol.QC4
            v.contains("HVDCP_3") || v.contains("QC3") || v.contains("QC_3") -> ChargeProtocol.QC3
            v.contains("HVDCP") || v.contains("QC2") || v.contains("QC_2") -> ChargeProtocol.QC2
            v.contains("WIRELESS") -> ChargeProtocol.WIRELESS
            v.contains("CDP") -> ChargeProtocol.CDP
            v.contains("DCP") -> ChargeProtocol.DCP
            v == "SDP" || v == "USB" || v == "C" -> ChargeProtocol.SDP
            v.contains("FLOAT") || v.contains("PROPRIETARY") -> ChargeProtocol.PROPRIETARY
            else -> null
        }
    }

    private fun isOnline(dir: File): Boolean = readLong(dir, "online") != 0L

    // ------------------------------------------------------------------ io helpers

    /**
     * Runs a reader only when sysfs is known to work. On a device that denies it, every
     * call collapses to a boolean test instead of a burst of failing file opens.
     */
    private inline fun <T> guarded(block: () -> T?): T? = if (isAvailable) block() else null

    /** Raw read that bypasses the availability gate, used to establish it in the first place. */
    private fun readUncached(dir: File, name: String): String? = read(dir, name)

    private fun firstLong(dir: File, names: List<String>): Long? =
        names.firstNotNullOfOrNull { readLong(dir, it) }

    private fun readLong(dir: File, name: String): Long? = read(dir, name)?.toLongOrNull()

    private fun read(dir: File, name: String): String? = runCatching {
        val f = File(dir, name)
        if (!f.canRead()) return null
        f.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Kernel nodes report micro-units, but a handful of vendor kernels report milli-units
     * or base units. Pick the scale from the magnitude.
     */
    private fun scaleToBase(raw: Long): Float {
        val a = abs(raw)
        return when {
            a > 1_000_000L -> raw / 1_000_000f
            a > 1_000L -> raw / 1_000f
            else -> raw.toFloat()
        }
    }

    private fun chargeToMah(raw: Long): Float? {
        val a = abs(raw)
        return when {
            a > 100_000L -> raw / 1_000f  // microamp-hours
            a > 100L -> raw.toFloat()     // already milliamp-hours
            else -> null
        }
    }

    private companion object {
        const val ROOT = "/sys/class/power_supply"
        const val NOMINAL_CELL_V = 3.85f
        const val OPEN_BRACKET = '['
        const val CLOSE_BRACKET = ']'
        val CURRENT_KEYS = listOf("current_now", "batt_current_now", "batt_current_ua_now")
        val INPUT_CURRENT_KEYS = listOf(
            "current_now", "input_current_now", "current_max",
            "input_current_settled", "input_current_limit",
        )
        val CHARGER_TYPES = setOf("USB", "MAINS", "USB_PD", "WIRELESS", "DOCK")
    }
}
