package com.voltcore.app.core.boost

import com.voltcore.app.core.battery.BatteryTelemetry
import com.voltcore.app.core.data.VoltPrefs
import com.voltcore.app.core.data.VoltSettings
import com.voltcore.app.core.privileged.FreezeMethod
import com.voltcore.app.core.privileged.PrivilegeManager
import com.voltcore.app.core.privileged.PrivilegeResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class BoostPhase { IDLE, BASELINE, APPLYING, VERIFYING, DONE, FAILED }

/** One thing boost tried to do, and whether it worked. */
data class BoostAction(
    val title: String,
    val detail: String,
    val applied: Boolean,
    val skippedReason: String? = null,
)

data class BoostState(
    val phase: BoostPhase = BoostPhase.IDLE,
    val progress: Float = 0f,
    /** Mean charging current before boost, mA. */
    val baselineMa: Float? = null,
    /** Mean charging current after boost, mA. */
    val resultMa: Float? = null,
    val actions: List<BoostAction> = emptyList(),
    val appsFrozen: Int = 0,
    val message: String? = null,
) {
    val gainMa: Float?
        get() {
            val a = baselineMa ?: return null
            val b = resultMa ?: return null
            return b - a
        }

    /** Percentage change; null when there is no usable baseline. */
    val gainPercent: Float?
        get() {
            val a = baselineMa ?: return null
            val g = gainMa ?: return null
            if (a <= 50f) return null
            return g / a * 100f
        }
}

/**
 * Reduces what the phone is spending so that more of the charger's output reaches the cell,
 * then measures whether it actually helped.
 *
 * A note on what this is not. No app can make a charger deliver more power on an unrooted
 * phone: the current is negotiated between the charger and the kernel's charging driver,
 * and nothing in the SDK can raise it. What is genuinely available is the other side of
 * the equation — the phone's own draw. Every milliamp the system is not spending on a
 * background app is a milliamp that ends up in the battery instead.
 *
 * So rather than showing an animation and claiming a speed-up, this samples the real
 * charging current before and after, and reports the measured difference including when
 * that difference is nil. Charging current also drifts on its own with level and
 * temperature, which is why the UI presents the number as an observation, not a promise.
 */
class BoostEngine(
    private val telemetry: BatteryTelemetry,
    private val privilege: PrivilegeManager,
    private val prefs: VoltPrefs,
) {

    private val _state = MutableStateFlow(BoostState())
    val state: StateFlow<BoostState> = _state.asStateFlow()

    /** Packages boost suspended, so they can be restored afterwards. */
    private val suspendedByBoost = mutableSetOf<String>()

    suspend fun boost() {
        if (_state.value.phase != BoostPhase.IDLE && _state.value.phase != BoostPhase.DONE) return

        val settings = prefs.settings.first()
        _state.value = BoostState(phase = BoostPhase.BASELINE, message = "Measuring current draw…")

        val baseline = sampleCurrent(BASELINE_SAMPLES) { done ->
            _state.value = _state.value.copy(progress = done * 0.35f)
        }

        _state.value = _state.value.copy(
            phase = BoostPhase.APPLYING,
            baselineMa = baseline,
            progress = 0.35f,
            message = "Freeing up power…",
        )

        val actions = applyActions(settings)

        _state.value = _state.value.copy(
            actions = actions,
            progress = 0.6f,
            phase = BoostPhase.VERIFYING,
            message = "Checking what changed…",
        )

        // Let the system settle before re-measuring, or the reading captures the cost of
        // the freeze itself rather than the benefit.
        delay(SETTLE_MS)

        val after = sampleCurrent(VERIFY_SAMPLES) { done ->
            _state.value = _state.value.copy(progress = 0.6f + done * 0.4f)
        }

        _state.value = _state.value.copy(
            phase = BoostPhase.DONE,
            resultMa = after,
            progress = 1f,
            message = null,
        )
    }

    /** Undoes the reversible parts: suspended apps come back. */
    suspend fun restore() {
        val settings = prefs.settings.first()
        for (pkg in suspendedByBoost.toList()) {
            privilege.unfreeze(pkg, settings.freezeMethod)
        }
        suspendedByBoost.clear()
        _state.value = BoostState()
    }

    fun dismiss() {
        _state.value = BoostState()
    }

    // ------------------------------------------------------------------ actions

    private suspend fun applyActions(settings: VoltSettings): List<BoostAction> {
        val actions = mutableListOf<BoostAction>()
        val backend = privilege.activeBackend()

        // 1. Stop the apps the user picked.
        if (settings.boostFreezesApps) {
            val targets = settings.frozenPackages.toList()
            when {
                targets.isEmpty() -> actions += BoostAction(
                    "Pause background apps",
                    "No apps selected yet",
                    applied = false,
                    skippedReason = "Choose apps in the Freezer tab first.",
                )

                else -> {
                    var frozen = 0
                    for (pkg in targets) {
                        val result = privilege.freeze(pkg, settings.freezeMethod)
                        if (result is PrivilegeResult.Success) {
                            frozen++
                            if (settings.freezeMethod != FreezeMethod.FORCE_STOP) {
                                suspendedByBoost += pkg
                            }
                        }
                    }
                    _state.value = _state.value.copy(appsFrozen = frozen)
                    actions += BoostAction(
                        "Pause background apps",
                        "$frozen of ${targets.size} stopped",
                        applied = frozen > 0,
                        skippedReason = if (frozen == 0) {
                            "Set up Shizuku to let VoltCore stop apps."
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // 2. Push those apps into the most restrictive standby bucket so the system
        //    stops handing them jobs and alarms while charging.
        if (settings.boostRestrictsBuckets) {
            if (backend == null) {
                actions += BoostAction(
                    "Restrict background work",
                    "Needs Shizuku",
                    applied = false,
                    skippedReason = "Requires shell access.",
                )
            } else {
                var ok = 0
                for (pkg in settings.frozenPackages) {
                    if (backend.setStandbyBucket(pkg, "restricted") is PrivilegeResult.Success) ok++
                }
                actions += BoostAction(
                    "Restrict background work",
                    "$ok apps moved to the restricted bucket",
                    applied = ok > 0,
                )
            }
        }

        // 3. Battery saver, if the user opted in. Off by default because it also throttles
        //    the foreground, which people notice and blame on the phone.
        if (settings.boostEnablesBatterySaver) {
            val result = backend?.putGlobalSetting("low_power", "1")
            actions += BoostAction(
                "Turn on Battery Saver",
                if (result is PrivilegeResult.Success) "Enabled" else "Could not change it",
                applied = result is PrivilegeResult.Success,
                skippedReason = if (result !is PrivilegeResult.Success) "Requires Shizuku." else null,
            )
        }

        return actions
    }

    // ------------------------------------------------------------------ measurement

    /**
     * Mean charging current over a short window, ignoring the first reading because it is
     * often stale, and discarding the extremes so one spike does not define the baseline.
     */
    private suspend fun sampleCurrent(
        samples: Int,
        onProgress: (Float) -> Unit,
    ): Float? {
        val readings = mutableListOf<Float>()
        repeat(samples) { i ->
            val snapshot = telemetry.read()
            snapshot.currentMa?.let { if (i > 0) readings += it }
            onProgress((i + 1) / samples.toFloat())
            delay(SAMPLE_INTERVAL_MS)
        }
        if (readings.size < 3) return readings.average().toFloat().takeIf { readings.isNotEmpty() }

        val sorted = readings.sorted()
        val trimmed = sorted.subList(1, sorted.size - 1)
        return trimmed.average().toFloat()
    }

    private companion object {
        const val BASELINE_SAMPLES = 6
        const val VERIFY_SAMPLES = 6
        const val SAMPLE_INTERVAL_MS = 900L
        const val SETTLE_MS = 2500L
    }
}
