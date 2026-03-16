// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief

/**
 * The full component vocabulary for ARIA's home screen Brief.
 * Every card on the home screen is one of these types.
 * New types are added deliberately, not on demand.
 */
sealed class BriefItem {

    /** Unique key for LazyColumn/AnimatedVisibility stability. */
    abstract fun stableKey(): String

    /** An alert that ARIA has already assessed — includes ARIA's verdict. */
    data class AlertAssessed(
        val icon: String,
        val headline: String,
        val subtext: String?,
        val severity: AlertSeverity,
        val action: BriefAction?,
    ) : BriefItem() {
        override fun stableKey() = "alert_${headline.hashCode()}"
    }

    /** A nudge for something time-sensitive the user might want to act on. */
    data class ReminderNudge(
        val icon: String,
        val headline: String,
        val subtext: String?,
        val action: BriefAction?,
    ) : BriefItem() {
        override fun stableKey() = "reminder_${headline.hashCode()}"
    }

    /** A calendar event coming up soon. */
    data class CalendarEvent(
        val title: String,
        val timeDescription: String,
        val location: String?,
        val primaryAction: BriefAction,
        val secondaryAction: BriefAction? = null,
    ) : BriefItem() {
        override fun stableKey() = "calendar_${title.hashCode()}_$timeDescription"
    }

    /** A media item the user was consuming and can resume. */
    data class MediaResume(
        val title: String,
        val subtitle: String,
        val thumbnailUri: String?,
        val resumeAction: BriefAction,
    ) : BriefItem() {
        override fun stableKey() = "media_${title.hashCode()}"
    }

    /** Live data from an MCP source (Phase 8+ — defined now as a seam). */
    data class LiveDataCard(
        val sourceId: String,
        val icon: String,
        val headline: String,
        val subtext: String?,
        val action: BriefAction?,
        val refreshedAt: Long,
    ) : BriefItem() {
        override fun stableKey() = "live_${sourceId}_${headline.hashCode()}"
    }

    /** A proactive suggestion ARIA is making based on pattern detection. */
    data class ProactiveSuggestion(
        val headline: String,
        val rationale: String,
        val action: BriefAction,
        val dismissible: Boolean = true,
    ) : BriefItem() {
        override fun stableKey() = "proactive_${headline.hashCode()}"
    }

    /** Venue-aware card shown when near a known location. */
    data class VenueCard(
        val venueName: String,
        val venueCategory: String,
        val headline: String,
        val actions: List<BriefAction>,
    ) : BriefItem() {
        override fun stableKey() = "venue_${venueName.hashCode()}"
    }

    /**
     * Compact weather + context line — always present, minimal footprint.
     * Rendered in the greeting area, NOT in the scrollable brief list.
     */
    data class ContextBar(
        val weatherLine: String,
        val locationHint: String?,
        val alertCount: Int = 0,
    ) : BriefItem() {
        override fun stableKey() = "contextbar"
    }

    fun isDismissible(): Boolean = when (this) {
        is AlertAssessed -> severity != AlertSeverity.CRITICAL
        is ReminderNudge -> true
        is CalendarEvent -> false
        is MediaResume -> true
        is LiveDataCard -> true
        is ProactiveSuggestion -> dismissible
        is VenueCard -> true
        is ContextBar -> false
    }
}

data class BriefAction(
    val label: String,
    val intentUri: String?,
    val mcpToolCall: McpToolCall? = null,
)

/** Placeholder for Phase 8 MCP integration. */
data class McpToolCall(
    val serverId: String,
    val toolName: String,
    val arguments: Map<String, String>,
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }
