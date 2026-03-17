// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import com.aria.launcher.aria.data.UpcomingCalendarEvent
import com.aria.launcher.aria.data.VenueCategory
import com.aria.launcher.aria.data.VisitContext

private val appointmentKeywords = Regex(
    "appointment|doctor|dr\\.|checkup|dentist|therapy|infusion|procedure",
    setOf(RegexOption.IGNORE_CASE),
)

/**
 * Heuristic inference of what the user is doing at this venue.
 * Used to disambiguate app surfaces — e.g., a healthcare venue visited daily
 * (likely_workplace) gets Slack/Teams suggestions instead of MyChart.
 */
fun inferVisitContext(
    venueCategory: VenueCategory,
    visitCount: Int,
    averageVisitDurationMinutes: Long,
    dayType: DayType,
    calendarEvents: List<UpcomingCalendarEvent>,
): VisitContext {
    // High-frequency + long duration + weekday → probably work
    if (visitCount > 8 && averageVisitDurationMinutes > 240 && dayType == DayType.WEEKDAY) {
        return VisitContext.LIKELY_WORKPLACE
    }

    // Calendar event has appointment-related language
    if (calendarEvents.any { appointmentKeywords.containsMatchIn(it.title) }) {
        return VisitContext.LIKELY_APPOINTMENT
    }

    // Infrequent + short → visitor or one-off
    if (visitCount < 3 && averageVisitDurationMinutes < 120) {
        return VisitContext.LIKELY_VISITOR
    }

    return VisitContext.UNKNOWN
}
