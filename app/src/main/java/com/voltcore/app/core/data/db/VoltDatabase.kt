package com.voltcore.app.core.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One recorded reading.
 *
 * Stored as nullable columns rather than sentinel zeros so a device that cannot report
 * current does not fill the history with fake flat lines.
 */
@Entity(tableName = "samples")
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val sessionId: Long?,
    val levelPercent: Int,
    val currentMa: Float?,
    val voltageV: Float?,
    val temperatureC: Float?,
    val chargerVoltageV: Float?,
    val isCharging: Boolean,
    val screenOn: Boolean,
)

/** A single plug-in to unplug span. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val startLevel: Int,
    val endLevel: Int? = null,
    val plugLabel: String,
    val protocolLabel: String,
    val peakCurrentMa: Float? = null,
    val averageCurrentMa: Float? = null,
    val peakPowerW: Float? = null,
    val peakTemperatureC: Float? = null,
    /** Charge actually added, mAh, from the fuel gauge rather than from the percentage. */
    val addedMah: Float? = null,
) {
    val durationMs: Long? get() = endedAtMs?.let { it - startedAtMs }
    val isOpen: Boolean get() = endedAtMs == null
    val levelGained: Int? get() = endLevel?.let { it - startLevel }
}

@Dao
interface VoltDao {

    // --- samples ---

    @Insert
    suspend fun insertSample(sample: SampleEntity)

    @Query("SELECT * FROM samples WHERE timestampMs >= :since ORDER BY timestampMs ASC")
    fun samplesSince(since: Long): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun samplesForSession(sessionId: Long): List<SampleEntity>

    @Query("DELETE FROM samples WHERE timestampMs < :before")
    suspend fun pruneSamples(before: Long)

    // --- sessions ---

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT :limit")
    fun recentSessions(limit: Int = 50): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): SessionEntity?

    @Query("DELETE FROM sessions WHERE startedAtMs < :before")
    suspend fun pruneSessions(before: Long)

    @Query("DELETE FROM samples")
    suspend fun clearSamples()

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()
}

@Database(
    entities = [SampleEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VoltDatabase : RoomDatabase() {

    abstract fun dao(): VoltDao

    companion object {
        @Volatile
        private var instance: VoltDatabase? = null

        fun get(context: Context): VoltDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VoltDatabase::class.java,
                "voltcore.db",
            )
                // History is a convenience, not a record of value; if the schema moves
                // on, starting over beats blocking the app on a migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
