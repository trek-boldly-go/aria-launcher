// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.data

/** Twelve-value venue classification enum used by the SSID intelligence stack. */
enum class VenueCategory {
    FAST_FOOD,
    COFFEE,
    RETAIL,
    HEALTHCARE,
    HOTEL,
    TRAVEL,
    OFFICE,
    EDUCATION,
    ENTERTAINMENT,
    HOME, // user-designated
    WORK, // user-designated
    UNKNOWN,
}

/** Inferred role of the user at this venue based on visit pattern + calendar. */
enum class VisitContext {
    LIKELY_WORKPLACE, // high frequency, long duration, weekday pattern
    LIKELY_APPOINTMENT, // calendar event matches, short visit
    LIKELY_VISITOR, // infrequent, short
    UNKNOWN,
}

/** App suggestion with relevance score — only surfaces if the app is installed. */
data class AppAffinity(
    val packageName: String,
    val relevanceScore: Float, // 0.0–1.0
)
