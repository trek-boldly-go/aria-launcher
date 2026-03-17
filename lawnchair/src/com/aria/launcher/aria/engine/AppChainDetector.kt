// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.app.usage.UsageEvents
import android.util.Log
import com.aria.launcher.aria.data.AppChain
import com.aria.launcher.aria.data.AppUsageEventDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects app-open chains: apps that are consistently opened within [windowMinutes]
 * of each other over a 30-day rolling window.
 *
 * Runs nightly inside [NightlyPredictionWorker]. Results are stored in [AppChain]
 * and queried at unlock time in [AriaHomeState] to boost follow-up predictions.
 */
@Singleton
class AppChainDetector @Inject constructor(
    private val dao: AppUsageEventDao,
) {

    /**
     * Scans the last [days] of MOVE_TO_FOREGROUND events and finds app pairs
     * that appear together at least [minOccurrences] times within [windowMinutes].
     */
    suspend fun detectChains(
        windowMinutes: Int = 3,
        minOccurrences: Int = 5,
        days: Int = 30,
    ): List<AppChain> {
        val sinceMs = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        // getEventsSince returns DESC — reverse to get chronological ASC order
        val events = dao.getEventsSince(sinceMs)
            .filter { it.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND }
            .reversed()

        if (events.size < 2) {
            Log.d(TAG, "Not enough events to detect chains (${events.size})")
            return emptyList()
        }

        val chains = mutableMapOf<Pair<String, String>, Int>()
        val windowMs = windowMinutes * 60 * 1000L

        for (i in 0 until events.size - 1) {
            val current = events[i]
            val next = events[i + 1]
            val deltaMs = next.timestamp - current.timestamp

            if (deltaMs in 1..windowMs && current.packageName != next.packageName) {
                val pair = current.packageName to next.packageName
                chains[pair] = (chains[pair] ?: 0) + 1
            }
        }

        val detected = chains
            .filter { it.value >= minOccurrences }
            .map { (pair, count) ->
                AppChain(trigger = pair.first, followUp = pair.second, occurrences = count)
            }

        Log.d(TAG, "Detected ${detected.size} chains from ${events.size} foreground events")
        return detected
    }

    companion object {
        private const val TAG = "ARIA.AppChainDetector"
    }
}
