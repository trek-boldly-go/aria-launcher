package com.aria.launcher.aria.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads raw events from [UsageStatsManager] and persists them as [AppUsageEvent] rows,
 * annotated with the current context snapshot from [ContextSignalManager].
 *
 * Requires [android.permission.PACKAGE_USAGE_STATS] — a "special" permission that must
 * be granted by the user in Settings > Special App Access > Usage access.
 */
@Singleton
class UsageStatsCollector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStatsManager: UsageStatsManager,
    private val repository: UsageDataRepository,
    private val contextSignalManager: ContextSignalManager,
) {
    /**
     * Returns true if the PACKAGE_USAGE_STATS permission has been granted.
     * The permission cannot be requested at runtime — the user must grant it manually.
     */
    fun hasPermission(): Boolean {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60_000L
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime,
        )
        return stats != null && stats.isNotEmpty()
    }

    /**
     * Reads usage events for the past [windowMs] milliseconds and writes new
     * [AppUsageEvent] rows to the database. Only foreground/background transitions
     * are stored — not every event type.
     */
    suspend fun collectAndStore(windowMs: Long = DEFAULT_WINDOW_MS) {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - windowMs

        val rawEvents = usageStatsManager.queryEvents(startTime, endTime)
        val contextSnapshot = contextSignalManager.snapshot()
        val cal = Calendar.getInstance()
        val batch = mutableListOf<AppUsageEvent>()
        val event = UsageEvents.Event()

        while (rawEvents.hasNextEvent()) {
            rawEvents.getNextEvent(event)
            val type = event.eventType
            if (type != UsageEvents.Event.MOVE_TO_FOREGROUND &&
                type != UsageEvents.Event.MOVE_TO_BACKGROUND
            ) {
                continue
            }

            cal.timeInMillis = event.timeStamp
            batch += AppUsageEvent(
                packageName = event.packageName,
                timestamp = event.timeStamp,
                eventType = type,
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                isCharging = contextSnapshot.isCharging,
                wifiSsid = contextSnapshot.wifiSsid,
                detectedActivity = contextSnapshot.detectedActivity,
            )
        }

        if (batch.isNotEmpty()) {
            repository.recordEvents(batch)
            Log.d(TAG, "Stored ${batch.size} usage events (context: charging=${contextSnapshot.isCharging}, wifi=${contextSnapshot.wifiSsid}, activity=${contextSnapshot.detectedActivity})")
        } else {
            Log.d(TAG, "No foreground/background events found in window")
        }
    }

    companion object {
        private const val TAG = "ARIA.UsageCollector"

        /** Default lookback window: last 24 hours. Prevents duplicate ingestion when
         *  the worker runs frequently — Room's REPLACE strategy deduplicates by PK. */
        const val DEFAULT_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}
