// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.NearbyWifiScanner
import com.aria.launcher.aria.data.UpcomingCalendarEvent
import com.aria.launcher.aria.data.UsageDataRepository

/**
 * Rich context snapshot used by the Brief system and editorial engine.
 * Supersedes the thin [ContextKey] tuple for Brief generation while
 * keeping ContextKey for backward-compatible prediction lookups.
 */
data class AriaContext(
    // Time
    val timestampMs: Long,
    val contextKey: ContextKey,

    // Device state
    val isCharging: Boolean,
    val wifiSsid: String?,
    val detectedActivity: Int?,
    val isAndroidAutoConnected: Boolean,
    val connectedCarName: String?,

    // Nearby networks
    val nearbySSIDs: List<String>,

    // Calendar
    val upcomingEvents: List<UpcomingCalendarEvent>,

    // Recent apps (last 2 hours, foreground only)
    val recentAppPackages: List<String>,

    // Venue (populated by Session 9 SsidClassificationService)
    val currentVenueCategory: String? = null,
    val visitContext: String? = null,

    // Rules that fired for this context (populated by Session 10 evaluator)
    val firedRules: List<FiredRule> = emptyList(),
) {
    /**
     * Bucket hash for distinctUntilChanged — Brief only regenerates
     * when this hash changes, not on every signal fluctuation.
     */
    fun bucketHash(): Int {
        var result = contextKey.hashCode()
        result = 31 * result + isCharging.hashCode()
        result = 31 * result + (wifiSsid?.hashCode() ?: 0)
        result = 31 * result + isAndroidAutoConnected.hashCode()
        result = 31 * result + upcomingEvents.map { it.title }.hashCode()
        result = 31 * result + (currentVenueCategory?.hashCode() ?: 0)
        result = 31 * result + firedRules.map { it.ruleId }.hashCode()
        return result
    }

    companion object {
        suspend fun build(
            contextSignalManager: ContextSignalManager,
            calendarEventProvider: CalendarEventProvider,
            usageDataRepository: UsageDataRepository,
            wifiScanner: NearbyWifiScanner,
            homeWifiSsid: String?,
            workWifiSsid: String?,
        ): AriaContext {
            val snapshot = contextSignalManager.snapshot()
            val now = System.currentTimeMillis()

            val contextKey = ContextKey.current(
                wifiSsid = snapshot.wifiSsid,
                detectedActivity = snapshot.detectedActivity,
                homeWifiSsid = homeWifiSsid,
                workWifiSsid = workWifiSsid,
                isAndroidAutoConnected = snapshot.isAndroidAutoConnected,
            )

            val upcomingEvents = calendarEventProvider.getUpcomingEvents(windowMinutes = 120)

            val twoHoursAgo = now - 2 * 60 * 60 * 1000L
            val recentEvents = usageDataRepository.getEventsForTraining(windowDays = 1)
            val recentApps = recentEvents
                .filter { it.timestamp >= twoHoursAgo && it.eventType == 1 }
                .sortedByDescending { it.timestamp }
                .map { it.packageName }
                .distinct()
                .take(10)

            return AriaContext(
                timestampMs = now,
                contextKey = contextKey,
                isCharging = snapshot.isCharging,
                wifiSsid = snapshot.wifiSsid,
                detectedActivity = snapshot.detectedActivity,
                isAndroidAutoConnected = snapshot.isAndroidAutoConnected,
                connectedCarName = snapshot.connectedCarName,
                nearbySSIDs = wifiScanner.nearbySSIDs.value,
                upcomingEvents = upcomingEvents,
                recentAppPackages = recentApps,
            )
        }
    }
}

/**
 * Lightweight reference to a rule that fired during context evaluation.
 * Carries the full [action] so [com.aria.launcher.aria.ui.AriaHomeState] can apply
 * SurfaceApp/SuppressApp boosts and convert ShowCard into ProactiveSuggestion BriefItems.
 */
data class FiredRule(
    val ruleId: Long,
    val action: com.aria.launcher.aria.engine.rules.RuleAction,
)
