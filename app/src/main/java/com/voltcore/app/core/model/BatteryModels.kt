package com.voltcore.app.core.model

import android.os.BatteryManager

/** What the battery is doing right now. */
enum class ChargeStatus { CHARGING, DISCHARGING, FULL, NOT_CHARGING, UNKNOWN;
    val isCharging: Boolean get() = this == CHARGING || this == FULL

    companion object {
        fun fromAndroid(raw: Int): ChargeStatus = when (raw) {
            BatteryManager.BATTERY_STATUS_CHARGING -> CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> NOT_CHARGING
            else -> UNKNOWN
        }
    }
}

/** How the device is physically connected to power. */
enum class PlugType(val label: String) {
    NONE("Battery"),
    AC("AC charger"),
    USB("USB"),
    WIRELESS("Wireless"),
    DOCK("Dock");

    companion object {
        fun fromAndroid(raw: Int): PlugType = when (raw) {
            BatteryManager.BATTERY_PLUGGED_AC -> AC
            BatteryManager.BATTERY_PLUGGED_USB -> USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> WIRELESS
            4 /* BATTERY_PLUGGED_DOCK, hidden until API 34 */ -> DOCK
            else -> NONE
        }
    }
}

/**
 * The negotiated charging protocol, as reported by the kernel. Only some devices
 * expose this; [UNKNOWN] simply means the node was absent or unreadable.
 */
enum class ChargeProtocol(val label: String) {
    UNKNOWN("Unknown"),
    SDP("USB 2.0 (SDP)"),
    CDP("Charging port (CDP)"),
    DCP("Standard charger (DCP)"),
    QC2("Quick Charge 2.0"),
    QC3("Quick Charge 3.0"),
    QC4("Quick Charge 4+"),
    PD("USB Power Delivery"),
    PPS("USB PD PPS"),
    WIRELESS("Wireless"),
    PROPRIETARY("Proprietary fast charge"),
}

/** Coarse speed bucket, used for colour and copy in the UI. */
enum class ChargeSpeed(val label: String) {
    NONE("Not charging"),
    TRICKLE("Trickle"),
    SLOW("Slow"),
    NORMAL("Normal"),
    FAST("Fast"),
    ULTRA("Ultra fast");

    companion object {
        /** Buckets by delivered power, which is what the user actually feels. */
        fun fromWatts(w: Float, charging: Boolean): ChargeSpeed = when {
            !charging -> NONE
            w < 1.5f -> TRICKLE
            w < 5f -> SLOW
            w < 12f -> NORMAL
            w < 27f -> FAST
            else -> ULTRA
        }
    }
}

/** Battery health as the framework reports it. */
enum class BatteryHealth(val label: String) {
    UNKNOWN("Unknown"), GOOD("Good"), OVERHEAT("Overheating"),
    DEAD("Dead"), OVER_VOLTAGE("Over voltage"), UNSPECIFIED_FAILURE("Failure"),
    COLD("Cold");

    companion object {
        fun fromAndroid(raw: Int): BatteryHealth = when (raw) {
            BatteryManager.BATTERY_HEALTH_GOOD -> GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> UNSPECIFIED_FAILURE
            BatteryManager.BATTERY_HEALTH_COLD -> COLD
            else -> UNKNOWN
        }
    }
}

/**
 * One sample of everything VoltCore can see about the battery.
 *
 * Nullable fields are genuinely unavailable on the device rather than zero — the UI
 * must render "—" for them instead of inventing a number.
 */
data class BatterySnapshot(
    val timestampMs: Long = 0L,
    val levelPercent: Int = 0,
    /**
     * Battery level to a decimal place, e.g. 78.1.
     *
     * Android's own level extra is a whole number, so this is derived from the fuel
     * gauge's charge counter against full capacity, or from a device that reports a
     * finer scale than 100. It is null when neither is available — showing a fabricated
     * ".0" would imply precision the hardware never gave us.
     */
    val levelPrecise: Float? = null,
    /**
     * True when [levelPrecise] came from integrating current rather than from a charge
     * counter. The UI must mark it, because an estimate and a measurement are not the
     * same claim.
     */
    val levelIsEstimated: Boolean = false,
    val status: ChargeStatus = ChargeStatus.UNKNOWN,
    val plug: PlugType = PlugType.NONE,
    val health: BatteryHealth = BatteryHealth.UNKNOWN,
    val technology: String? = null,
    val temperatureC: Float? = null,
    /** Battery terminal voltage in volts. */
    val voltageV: Float? = null,
    /** Signed milliamps: positive charging, negative discharging. */
    val currentMa: Float? = null,
    /** Kernel's own rolling average, when exposed. */
    val avgCurrentMa: Float? = null,
    /** Charge remaining in the cell, mAh. */
    val chargeCounterMah: Float? = null,
    /** Present full-charge capacity, mAh — degrades with age. */
    val fullCapacityMah: Float? = null,
    /** Factory capacity, mAh. */
    val designCapacityMah: Float? = null,
    val cycleCount: Int? = null,
    val stateOfHealthPercent: Int? = null,
    /** Charger-side voltage in volts — 5/9/12/20 V reveals the negotiated PD profile. */
    val chargerVoltageV: Float? = null,
    val chargerCurrentMa: Float? = null,
    val protocol: ChargeProtocol = ChargeProtocol.UNKNOWN,
    val screenOn: Boolean = true,
) {
    /** Power crossing the battery terminals, W. Signed like [currentMa]. */
    val batteryPowerW: Float?
        get() {
            val v = voltageV ?: return null
            val i = currentMa ?: return null
            return v * i / 1000f
        }

    /** Power arriving from the charger, W — only when the kernel exposes both legs. */
    val inputPowerW: Float?
        get() {
            val v = chargerVoltageV ?: return null
            val i = chargerCurrentMa ?: return null
            return v * i / 1000f
        }

    val isCharging: Boolean get() = status.isCharging && (currentMa ?: 0f) > -20f

    val speed: ChargeSpeed
        get() = ChargeSpeed.fromWatts(
            inputPowerW ?: batteryPowerW?.coerceAtLeast(0f) ?: 0f,
            status.isCharging,
        )

    /** Percentage of factory capacity still usable. */
    val healthPercent: Int?
        get() {
            stateOfHealthPercent?.let { if (it in 1..100) return it }
            val full = fullCapacityMah ?: return null
            val design = designCapacityMah ?: return null
            if (design <= 0f) return null
            return ((full / design) * 100f).toInt().coerceIn(0, 100)
        }
}
