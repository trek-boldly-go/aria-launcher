package com.aria.launcher.aria.engine

import android.app.usage.UsageEvents
import android.util.Log
import com.aria.launcher.aria.data.AppPrediction
import com.aria.launcher.aria.data.AppUsageEvent
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.UsageDataRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier 1 prediction engine — rule-based frequency scoring.
 *
 * Groups historical [AppUsageEvent]s by their reconstructed [ContextKey], counts
 * how often each app was opened (MOVE_TO_FOREGROUND) in each context bucket,
 * normalises counts to 0.0–1.0 scores, and writes [AppPrediction] rows back to
 * Room. The home screen reads those rows at unlock time.
 *
 * Designed to run inside [NightlyPredictionWorker] during a charging window.
 * Tier 2 (TFLite) will layer on top once 14+ days of data are available.
 */
@Singleton
class PredictionEngine @Inject constructor(
    private val repository: UsageDataRepository,
    private val calendarEventProvider: CalendarEventProvider,
) {

    /**
     * Process the last [windowDays] of usage events and generate per-context predictions.
     *
     * @param homeWifiSsid user-configured home WiFi SSID (nullable if not configured)
     * @param workWifiSsid user-configured work WiFi SSID (nullable if not configured)
     */
    suspend fun generatePredictions(
        homeWifiSsid: String?,
        workWifiSsid: String?,
        windowDays: Int = 30,
    ) {
        val events = repository.getEventsForTraining(windowDays)
        if (events.isEmpty()) {
            Log.d(TAG, "No training events found in last $windowDays days")
            return
        }
        Log.d(TAG, "generatePredictions: ${events.size} training events over $windowDays days")

        // 1. Group foreground events by context key + package
        val foregroundEvents = events.filter { it.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND }

        // Map: contextKeyString -> (packageName -> openCount)
        val contextCounts = mutableMapOf<String, MutableMap<String, Int>>()

        for (event in foregroundEvents) {
            val key = ContextKey.fromEvent(
                hourOfDay = event.hourOfDay,
                dayOfWeek = event.dayOfWeek,
                wifiSsid = event.wifiSsid,
                detectedActivity = event.detectedActivity,
                homeWifiSsid = homeWifiSsid,
                workWifiSsid = workWifiSsid,
            ).toStringKey()

            val packageCounts = contextCounts.getOrPut(key) { mutableMapOf() }
            packageCounts[event.packageName] = (packageCounts[event.packageName] ?: 0) + 1
        }

        // 2. Normalise to 0.0–1.0 per context bucket
        val now = System.currentTimeMillis()
        val predictions = mutableListOf<AppPrediction>()

        for ((contextKey, packageCounts) in contextCounts) {
            val maxCount = packageCounts.values.maxOrNull() ?: continue
            if (maxCount == 0) continue

            for ((pkg, count) in packageCounts) {
                val score = count.toFloat() / maxCount.toFloat()
                // Only keep apps with meaningful signal
                if (score >= MIN_SCORE_THRESHOLD) {
                    predictions += AppPrediction(
                        packageName = pkg,
                        score = score,
                        lastUpdated = now,
                        contextKey = contextKey,
                    )
                }
            }
        }

        // 3. Boost apps related to upcoming calendar events
        boostMeetingApps(predictions)

        // 4. Write to database
        Log.d(TAG, "Generated ${predictions.size} predictions across ${contextCounts.size} context buckets")
        if (predictions.isNotEmpty()) {
            repository.savePredictions(predictions)
        }

        // 5. Clean up old events beyond retention window
        repository.pruneOldEvents(windowDays)
    }

    /**
     * If there's a calendar event in the next 30 min, boost meeting-related app scores.
     */
    private fun boostMeetingApps(predictions: MutableList<AppPrediction>) {
        if (!calendarEventProvider.hasUpcomingEvent(windowMinutes = 30)) return

        val now = System.currentTimeMillis()
        val boosts = mutableListOf<AppPrediction>()

        for (pkg in MEETING_PACKAGES) {
            val existingIndex = predictions.indexOfFirst { it.packageName == pkg }
            if (existingIndex >= 0) {
                val existing = predictions[existingIndex]
                predictions[existingIndex] = existing.copy(score = maxOf(existing.score, MEETING_BOOST_SCORE))
            } else {
                // Inject a meeting app even if it hasn't been used in this context before
                boosts += AppPrediction(
                    packageName = pkg,
                    score = MEETING_BOOST_SCORE,
                    lastUpdated = now,
                    contextKey = "MEETING_BOOST",
                )
            }
        }

        predictions += boosts
    }

    companion object {
        private const val TAG = "ARIA.PredictionEngine"
        private const val MIN_SCORE_THRESHOLD = 0.05f
        private const val MEETING_BOOST_SCORE = 0.9f

        private val MEETING_PACKAGES = setOf(
            "us.zoom.videomeetings",    // Zoom
            "com.microsoft.teams",       // Teams
            "com.google.android.apps.meetings", // Google Meet
            "com.slack",                 // Slack
            "com.google.android.calendar", // Google Calendar
        )
    }
}
