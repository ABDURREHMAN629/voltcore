package com.voltcore.app.core.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/**
 * One observation of the battery level, used to build the day and hour views.
 *
 * Deliberately tiny and long-lived, unlike [SampleEntity], which records every detail of a
 * charging session at 15-second resolution and is kept for days rather than weeks. Rows come
 * from two places: VoltCore's own periodic reading, and Android's battery history imported
 * over the `DUMP` permission. The timestamp is the key, so importing the same history twice
 * cannot duplicate anything.
 */
@Entity(tableName = "level_log")
data class LevelLogEntity(
    @PrimaryKey val timestampMs: Long,
    val level: Int,
    val plugged: Boolean,
    /** Screen state at a transition; null for periodic readings, where it is not a transition. */
    val screenOn: Boolean?,
    /** [SOURCE_VOLTCORE] or [SOURCE_ANDROID]. */
    val source: Int,
) {
    companion object {
        const val SOURCE_VOLTCORE = 0
        const val SOURCE_ANDROID = 1
    }
}

@Dao
interface VoltDao {

    // --- level log ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLevels(rows: List<LevelLogEntity>)

    @Query("SELECT * FROM level_log WHERE timestampMs >= :from AND timestampMs < :to ORDER BY timestampMs ASC")
    suspend fun levelsBetween(from: Long, to: Long): List<LevelLogEntity>

    @Query("SELECT * FROM level_log ORDER BY timestampMs DESC LIMIT 1")
    suspend fun lastLevel(): LevelLogEntity?

    @Query("SELECT MIN(timestampMs) FROM level_log")
    suspend fun firstLevelTimestamp(): Long?

    @Query("SELECT MAX(timestampMs) FROM level_log WHERE source = :source")
    suspend fun lastLevelTimestamp(source: Int): Long?

    @Query("DELETE FROM level_log WHERE timestampMs < :before")
    suspend fun pruneLevels(before: Long)

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
    entities = [SampleEntity::class, SessionEntity::class, LevelLogEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class VoltDatabase : RoomDatabase() {

    abstract fun dao(): VoltDao

    companion object {
        @Volatile
        private var instance: VoltDatabase? = null

        /**
         * Adds the level log. A real migration rather than the destructive fallback, because
         * people installing this update already have charging sessions recorded.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `level_log` (" +
                        "`timestampMs` INTEGER NOT NULL, " +
                        "`level` INTEGER NOT NULL, " +
                        "`plugged` INTEGER NOT NULL, " +
                        "`screenOn` INTEGER, " +
                        "`source` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`timestampMs`))",
                )
            }
        }

        fun get(context: Context): VoltDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VoltDatabase::class.java,
                "voltcore.db",
            )
                .addMigrations(MIGRATION_1_2)
                // Still a safety net for schema changes without a migration: history is a
                // convenience, and starting over beats refusing to open.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
