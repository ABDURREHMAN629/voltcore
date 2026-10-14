package com.voltcore.app.core.battery

import kotlin.math.abs
import kotlin.math.sign

/**
 * Estimates battery level to a decimal place by counting coulombs.
 *
 * Some devices expose a charge counter, and dividing it by full capacity gives a real
 * fractional percentage. Plenty of others — Xiaomi's HyperOS among them — return
 * `Long.MIN_VALUE` for `CHARGE_COUNTER`, `ENERGY_COUNTER` *and* `CURRENT_AVERAGE`, and
 * deny sysfs on top, leaving a whole-number level as the only thing on offer.
 *
 * What those devices still report is instantaneous current. Integrating it over time gives
 * the charge that has moved since the level last changed, and that is enough to place the
 * battery within the current percent.
 *
 * Two properties keep this from drifting into fiction:
 *
 *  - **It re-anchors on every whole-percent tick.** Error cannot accumulate past 1%,
 *    because each transition throws the running total away and starts again.
 *  - **The result is clamped to the reported integer's own band.** If the framework says
 *    72, the estimate can only ever be in [71.5, 72.5); it can never contradict the one
 *    number we know for certain.
 *
 * It also learns capacity while it runs: the charge that passes between two consecutive
 * ticks is, by definition, one percent of the battery.
 *
 * This is an estimate and the UI labels it as one. It is not a measurement, and the
 * distinction matters more than the extra digit does.
 */
class LevelEstimator(capacityMah: Float? = null) {

    /** Full-charge capacity in mAh, supplied by the user or learned from tick spacing. */
    var capacityMah: Float? = capacityMah
        private set

    /** True once capacity came from observation rather than a user-entered figure. */
    var capacityIsLearned: Boolean = false
        private set

    private var anchorLevel: Int? = null
    private var anchorTrue: Float = 0f
    private var direction: Int = 0
    private var accumulatedMah: Float = 0f
    private var lastSampleMs: Long = 0L

    /** Fired when capacity is learned or refined, so it can be persisted. */
    var onCapacityLearned: ((Float) -> Unit)? = null

    /** Lets the user's own figure override whatever was learned. */
    fun setUserCapacity(mah: Float?) {
        if (mah != null && mah > 0f) {
            capacityMah = mah
            capacityIsLearned = false
        }
    }

    /**
     * Supplies a starting capacity — typically the battery's factory rating — so decimals
     * are available from the first reading instead of only after a whole percent has
     * elapsed. Never overwrites a user figure or a learned one.
     */
    fun seedCapacity(mah: Float) {
        if (capacityMah == null && mah > 0f) capacityMah = mah
    }

    /**
     * Feeds one reading.
     *
     * @return the estimated level to a decimal place, or null while there is not yet
     *         enough information to say anything better than the whole number.
     */
    fun observe(
        levelPercent: Int,
        currentMa: Float?,
        timestampMs: Long,
    ): Float? {
        if (currentMa == null || timestampMs <= 0L) {
            lastSampleMs = timestampMs
            return null
        }

        val previous = lastSampleMs
        lastSampleMs = timestampMs
        if (previous > 0L) {
            val dtMs = (timestampMs - previous).coerceAtMost(MAX_GAP_MS)
            if (dtMs > 0L) {
                accumulatedMah += currentMa * (dtMs / 3_600_000f)
            }
        }

        val anchor = anchorLevel
        if (anchor == null) {
            anchorLevel = levelPercent
            anchorTrue = levelPercent.toFloat()
            accumulatedMah = 0f
            direction = 0
            return null
        }

        if (levelPercent != anchor) {
            val step = levelPercent - anchor
            if (abs(step) == 1 && direction != 0 && sign(step.toFloat()).toInt() == direction) {
                learnCapacity(abs(accumulatedMah))
            }
            direction = if (step > 0) 1 else -1
            anchorTrue = levelPercent - direction * 0.5f
            anchorLevel = levelPercent
            accumulatedMah = 0f
            return anchorTrue.coerceIn(levelPercent - 0.5f, levelPercent + 0.499f)
        }

        if (direction == 0) {
            direction = if (currentMa > 0f) 1 else -1
            return null
        }

        val capacity = capacityMah ?: return null
        if (capacity <= 0f) return null

        val perPercentMah = capacity / 100f
        val movedPercent = accumulatedMah / perPercentMah

        val estimate = anchorTrue + movedPercent
        return estimate.coerceIn(anchor - 0.5f, anchor + 0.499f).coerceIn(0f, 100f)
    }

    /**
     * The charge that passes between two consecutive whole-percent ticks is one percent of
     * the battery. Averaged with an exponential filter so one odd interval — a screen-off
     * gap, a thermal throttle — cannot swing the figure.
     */
    private fun learnCapacity(onePercentMah: Float) {
        if (onePercentMah <= 0f) return
        val candidate = onePercentMah * 100f
        if (candidate !in MIN_PLAUSIBLE_MAH..MAX_PLAUSIBLE_MAH) return

        val current = capacityMah
        val next = if (current == null || !capacityIsLearned) {
            candidate
        } else {
            current * (1f - LEARN_RATE) + candidate * LEARN_RATE
        }
        capacityMah = next
        capacityIsLearned = true
        onCapacityLearned?.invoke(next)
    }

    fun reset() {
        anchorLevel = null
        accumulatedMah = 0f
        direction = 0
        lastSampleMs = 0L
    }

    private companion object {
        /** Ignore gaps longer than this: the service was probably not running. */
        const val MAX_GAP_MS = 60_000L
        const val MIN_PLAUSIBLE_MAH = 800f
        const val MAX_PLAUSIBLE_MAH = 15_000f
        const val LEARN_RATE = 0.25f
    }
}
