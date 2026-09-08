package com.voltcore.app.core.data

import android.content.Context
import com.voltcore.app.core.data.db.SampleEntity
import com.voltcore.app.core.data.db.SessionEntity
import com.voltcore.app.core.data.db.VoltDatabase
import com.voltcore.app.core.model.BatterySnapshot
import kotlinx.coroutines.flow.Flow
import kotlin.math.max

/**
 * Records charging history.
 *
 * A "session" is one plug-in to unplug span. Charge added is taken from the fuel gauge's
 * charge counter rather than from the percentage, because percentage is quantised to 1%
 * — on a 5000 mAh battery that is a 50 mAh step, which is far too coarse to say anything
 * useful about how much a 20-minute top-up actually delivered.
 */
class HistoryRepository(context: Context) {

    private val dao = VoltDatabase.get(context).dao()

    private var openSessionId: Long? = null
    private var sessionStartChargeMah: Float? = null
    private var lastSampleAtMs = 0L

    fun recentSessions(limit: Int = 50): Flow<List<SessionEntity>> = dao.recentSessions(limit)

    fun samplesSince(sinceMs: Long): Flow<List<SampleEntity>> = dao.samplesSince(sinceMs)

    suspend fun samplesForSession(sessionId: Long): List<SampleEntity> =
        dao.samplesForSession(sessionId)

    /**
     * Folds one reading into the history, opening or closing a session as needed.
     * Cheap to call every tick: sample writes are throttled internally.
     */
    suspend fun record(snapshot: BatterySnapshot) {
        val charging = snapshot.status.isCharging

        if (charging && openSessionId == null) startSession(snapshot)
        if (!charging && openSessionId != null) endSession(snapshot)

        val now = snapshot.timestampMs
        if (now - lastSampleAtMs < SAMPLE_MIN_INTERVAL_MS) return
        lastSampleAtMs = now

        dao.insertSample(
            SampleEntity(
                timestampMs = now,
                sessionId = openSessionId,
                levelPercent = snapshot.levelPercent,
                currentMa = snapshot.currentMa,
                voltageV = snapshot.voltageV,
                temperatureC = snapshot.temperatureC,
                chargerVoltageV = snapshot.chargerVoltageV,
                isCharging = charging,
                screenOn = snapshot.screenOn,
            ),
        )

        if (openSessionId != null) updateRunningTotals(snapshot)
    }

    private suspend fun startSession(snapshot: BatterySnapshot) {
        // Recover a session left open by a crash or a reboot rather than stacking a new one.
        dao.openSession()?.let {
            openSessionId = it.id
            sessionStartChargeMah = snapshot.chargeCounterMah
            return
        }
        openSessionId = dao.insertSession(
            SessionEntity(
                startedAtMs = snapshot.timestampMs,
                startLevel = snapshot.levelPercent,
                plugLabel = snapshot.plug.label,
                protocolLabel = snapshot.protocol.label,
            ),
        )
        sessionStartChargeMah = snapshot.chargeCounterMah
    }

    private suspend fun endSession(snapshot: BatterySnapshot) {
        val id = openSessionId ?: return
        val session = dao.session(id) ?: run {
            openSessionId = null
            return
        }
        dao.updateSession(
            session.copy(
                endedAtMs = snapshot.timestampMs,
                endLevel = snapshot.levelPercent,
                addedMah = chargeAdded(snapshot),
            ),
        )
        openSessionId = null
        sessionStartChargeMah = null
    }

    private suspend fun updateRunningTotals(snapshot: BatterySnapshot) {
        val id = openSessionId ?: return
        val session = dao.session(id) ?: return
        val current = snapshot.currentMa
        val power = snapshot.inputPowerW ?: snapshot.batteryPowerW

        dao.updateSession(
            session.copy(
                peakCurrentMa = maxOfNullable(session.peakCurrentMa, current),
                peakPowerW = maxOfNullable(session.peakPowerW, power),
                peakTemperatureC = maxOfNullable(session.peakTemperatureC, snapshot.temperatureC),
                endLevel = snapshot.levelPercent,
                addedMah = chargeAdded(snapshot) ?: session.addedMah,
                protocolLabel = snapshot.protocol.label.takeIf { it.isNotBlank() }
                    ?: session.protocolLabel,
            ),
        )
    }

    private fun chargeAdded(snapshot: BatterySnapshot): Float? {
        val start = sessionStartChargeMah ?: return null
        val now = snapshot.chargeCounterMah ?: return null
        return (now - start).takeIf { it > 0f }
    }

    private fun maxOfNullable(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> max(a, b)
    }

    /** Drops readings older than the retention window so the database stays small. */
    suspend fun prune() {
        val cutoff = System.currentTimeMillis() - SAMPLE_RETENTION_MS
        dao.pruneSamples(cutoff)
        dao.pruneSessions(System.currentTimeMillis() - SESSION_RETENTION_MS)
    }

    suspend fun clearAll() {
        dao.clearSamples()
        dao.clearSessions()
        openSessionId = null
    }

    private companion object {
        /** One row every 15 s is plenty for a readable graph and keeps the table small. */
        const val SAMPLE_MIN_INTERVAL_MS = 15_000L
        const val SAMPLE_RETENTION_MS = 3L * 24 * 60 * 60 * 1000
        const val SESSION_RETENTION_MS = 60L * 24 * 60 * 60 * 1000
    }
}
