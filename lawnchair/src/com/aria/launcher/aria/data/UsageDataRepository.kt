package com.aria.launcher.aria.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(primaryKeys = ["packageName", "timestamp", "eventType"])
data class AppUsageEvent(
    val packageName: String,
    val timestamp: Long,          // epoch ms
    val eventType: Int,           // MOVE_TO_FOREGROUND = 1, MOVE_TO_BACKGROUND = 2
    val hourOfDay: Int,           // 0–23, derived
    val dayOfWeek: Int,           // 1=Sun, 7=Sat, derived
    val isCharging: Boolean,
    val wifiSsid: String?,        // null if not on WiFi
    val detectedActivity: Int?,   // from ActivityRecognitionClient (STILL=3, WALKING=7, IN_VEHICLE=0)
)

@Entity(primaryKeys = ["packageName", "contextKey"])
data class AppPrediction(
    val packageName: String,
    val score: Float,             // 0.0–1.0 likelihood for current context
    val lastUpdated: Long,
    val contextKey: String,       // e.g. "WEEKDAY_MORNING_UNKNOWN"
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface AppUsageEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AppUsageEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<AppUsageEvent>)

    @Query("SELECT * FROM AppUsageEvent WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    fun observeEventsSince(sinceMs: Long): Flow<List<AppUsageEvent>>

    @Query("SELECT * FROM AppUsageEvent WHERE timestamp >= :sinceMs ORDER BY timestamp DESC")
    suspend fun getEventsSince(sinceMs: Long): List<AppUsageEvent>

    @Query("SELECT * FROM AppUsageEvent WHERE packageName = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getEventsForPackage(pkg: String, limit: Int = 100): List<AppUsageEvent>

    @Query("DELETE FROM AppUsageEvent WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}

@Dao
interface AppPredictionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prediction: AppPrediction)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(predictions: List<AppPrediction>)

    @Query("SELECT * FROM AppPrediction WHERE contextKey = :contextKey ORDER BY score DESC")
    fun observePredictionsForContext(contextKey: String): Flow<List<AppPrediction>>

    @Query("SELECT * FROM AppPrediction WHERE contextKey = :contextKey ORDER BY score DESC LIMIT :limit")
    suspend fun getTopPredictions(contextKey: String, limit: Int = 6): List<AppPrediction>

    @Query("SELECT * FROM AppPrediction ORDER BY score DESC")
    suspend fun getAllPredictions(): List<AppPrediction>

    /**
     * Fallback: return the highest-scoring prediction per package across ALL contexts.
     * Used when no predictions match the current context key exactly.
     */
    @Query("""
        SELECT ap.packageName, ap.score, ap.lastUpdated, ap.contextKey
        FROM AppPrediction ap
        INNER JOIN (
            SELECT packageName, MAX(score) as maxScore
            FROM AppPrediction
            GROUP BY packageName
        ) best ON ap.packageName = best.packageName AND ap.score = best.maxScore
        ORDER BY ap.score DESC
    """)
    fun observeTopPredictionsAcrossContexts(): Flow<List<AppPrediction>>

    @Query("DELETE FROM AppPrediction WHERE packageName = :pkg")
    suspend fun deleteForPackage(pkg: String)

    @Query("DELETE FROM AppPrediction")
    suspend fun deleteAll()
}

// ---------------------------------------------------------------------------
// Repository
// ---------------------------------------------------------------------------

class UsageDataRepository(context: Context) {

    private val db = AriaDatabase.getInstance(context)
    private val eventDao = db.appUsageEventDao()
    private val predictionDao = db.appPredictionDao()

    // --- Event writes ---

    suspend fun recordEvent(event: AppUsageEvent) = eventDao.insert(event)

    suspend fun recordEvents(events: List<AppUsageEvent>) = eventDao.insertAll(events)

    suspend fun pruneOldEvents(retentionDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - retentionDays * 24 * 60 * 60 * 1000L
        eventDao.deleteOlderThan(cutoff)
    }

    // --- Event reads ---

    fun observeRecentEvents(windowDays: Int = 7): Flow<List<AppUsageEvent>> {
        val since = System.currentTimeMillis() - windowDays * 24 * 60 * 60 * 1000L
        return eventDao.observeEventsSince(since)
    }

    suspend fun getEventsForTraining(windowDays: Int = 30): List<AppUsageEvent> {
        val since = System.currentTimeMillis() - windowDays * 24 * 60 * 60 * 1000L
        return eventDao.getEventsSince(since)
    }

    // --- Prediction writes ---

    suspend fun savePredictions(predictions: List<AppPrediction>) =
        predictionDao.upsertAll(predictions)

    // --- Prediction reads ---

    fun observePredictions(contextKey: String): Flow<List<AppPrediction>> =
        predictionDao.observePredictionsForContext(contextKey)

    fun observePredictionsFallback(): Flow<List<AppPrediction>> =
        predictionDao.observeTopPredictionsAcrossContexts()

    suspend fun getTopApps(contextKey: String, limit: Int = 6): List<AppPrediction> =
        predictionDao.getTopPredictions(contextKey, limit)

    suspend fun getAllPredictions(): List<AppPrediction> =
        predictionDao.getAllPredictions()

    suspend fun clearAllPredictions() =
        predictionDao.deleteAll()
}
