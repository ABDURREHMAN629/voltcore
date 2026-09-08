package com.voltcore.app.core.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.File

/** One probed data source and what it actually returned on this device. */
data class DiagnosticEntry(
    val name: String,
    val value: String?,
    val available: Boolean,
)

/**
 * Probes every source VoltCore can read and reports what this particular device gives up.
 *
 * This exists because battery data availability varies wildly and silently. Xiaomi's
 * HyperOS, for one, denies even the shell user access to /sys/class/power_supply, which
 * removes charger voltage, PD detection, cycle count and design capacity in one stroke.
 * Rather than leaving the user staring at unexplained dashes, the app can show them
 * exactly which sources their phone permits.
 */
class TelemetryDiagnostics(private val context: Context) {

    private val bm: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    fun probe(): List<DiagnosticEntry> {
        val out = mutableListOf<DiagnosticEntry>()
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        // --- framework properties ---
        out += property("Capacity (%)", BatteryManager.BATTERY_PROPERTY_CAPACITY)
        out += property("Current now", BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        out += property("Current average", BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        out += property("Charge counter", BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        out += property("Energy counter", BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)

        // --- sticky broadcast extras ---
        out += entry("Level", intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)?.takeIf { it >= 0 })
        out += entry("Scale", intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)?.takeIf { it > 0 })
        out += entry("Voltage (mV)", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 })
        out += entry(
            "Temperature (0.1C)",
            intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            out += entry(
                "Cycle count",
                intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it > 0 },
            )
        }

        // --- sysfs ---
        val sysfsRoot = File("/sys/class/power_supply")
        val listed = runCatching { sysfsRoot.listFiles()?.size }.getOrNull()
        out += DiagnosticEntry(
            "sysfs directory",
            if (listed != null && listed > 0) "$listed nodes" else "not listable",
            listed != null && listed > 0,
        )
        val probe = SysfsProbe()
        out += DiagnosticEntry("sysfs readable", if (probe.isAvailable) "yes" else "denied", probe.isAvailable)
        out += entry("Charger voltage (V)", probe.chargerVoltageV())
        out += entry("Charger current (mA)", probe.chargerCurrentMa())
        out += entry("Full capacity (mAh)", probe.fullCapacityMah())
        out += entry("Design capacity (mAh)", probe.designCapacityMah())
        out += entry("Cycle count (sysfs)", probe.cycleCount())
        out += DiagnosticEntry("Charge protocol", probe.chargeProtocol().label, true)

        return out
    }

    private fun property(name: String, id: Int): DiagnosticEntry {
        val raw = runCatching { bm?.getLongProperty(id) }.getOrNull()
        val usable = raw != null &&
            raw != Long.MIN_VALUE &&
            raw != Int.MIN_VALUE.toLong() &&
            raw != 0L
        return DiagnosticEntry(name, raw?.toString(), usable)
    }

    private fun entry(name: String, value: Any?): DiagnosticEntry =
        DiagnosticEntry(name, value?.toString(), value != null)

    /** Dumps the probe to logcat once, to make field diagnosis possible over adb. */
    fun log() {
        Log.i(TAG, "--- VoltCore data sources on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) ---")
        probe().forEach { e ->
            Log.i(TAG, String.format("%-24s %-14s %s", e.name, e.value ?: "-", if (e.available) "OK" else "UNAVAILABLE"))
        }
    }

    private companion object {
        const val TAG = "VoltCoreDiag"
    }
}
