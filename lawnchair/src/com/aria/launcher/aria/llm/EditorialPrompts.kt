// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.llm

import com.aria.launcher.aria.engine.AriaContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the editorial system prompt for ARIA's LLM Brief curation.
 * Called once per context change, not per unlock.
 */
object EditorialPrompts {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    fun buildEditorialSystemPrompt(context: AriaContext): String {
        val key = context.contextKey
        val formattedTime = timeFormat.format(Date(context.timestampMs))
        val activityStr = context.detectedActivity?.let { activityLabel(it) } ?: "unknown"
        val eventsStr = if (context.upcomingEvents.isEmpty()) {
            "none"
        } else {
            context.upcomingEvents.joinToString("; ") { it.title }
        }
        val recentAppsStr = if (context.recentAppPackages.isEmpty()) {
            "none"
        } else {
            context.recentAppPackages.take(5).joinToString(", ")
        }
        val rulesStr = if (context.firedRules.isEmpty()) {
            "none"
        } else {
            context.firedRules.joinToString("; ") { "rule#${it.ruleId}(${it.action::class.simpleName})" }
        }

        return """
            You are ARIA's (an android launcher) editorial engine. Your job is to decide what appears on the
            user's home screen right now, based on their current context and signals
            from ARIA's prediction engine.

            Current context:
            - Time: $formattedTime (${key.timeBucket})
            - Day: ${key.dayType}
            - Location: ${key.location}
            - Detected activity: $activityStr
            - Upcoming calendar events: $eventsStr
            - Recently used apps: $recentAppsStr
            - Current venue: ${context.currentVenueCategory ?: "unknown"}
            - Active user rules that fired: $rulesStr

            Respond ONLY with valid JSON. No markdown, no explanation, no preamble:

            {
              "brief": [
                {
                  "type": "<valid type>",
                  "icon": "<material symbol name>",
                  "headline": "<max 6 words — ARIA's judgment, not raw data>",
                  "subtext": "<max 12 words, or null>",
                  "action": { "label": "<max 3 words>", "intentUri": "<uri or null>" }
                }
              ]
            }

            Valid types: alert_assessed, reminder_nudge, calendar_event, media_resume,
            proactive_suggestion, venue_card, live_data_card

            Rules:
            - Maximum 5 items. Minimum 0 — empty is better than noisy.
            - Headlines must be ARIA's judgment, not forwarded data.
              BAD: "Flood Advisory issued March 15 at 9:02PM CDT until March 16"
              GOOD: "Flood advisory — your area isn't affected"
            - Never show more than one weather item.
            - Calendar events within 30 minutes always appear.
            - If a user rule fired, its corresponding action takes priority.
            - When in doubt, show less.
        """.trimIndent()
    }

    private fun activityLabel(activityType: Int): String = when (activityType) {
        0 -> "in vehicle"
        1 -> "on bicycle"
        2 -> "on foot"
        3 -> "still"
        4 -> "unknown"
        5 -> "tilting"
        7 -> "walking"
        8 -> "running"
        else -> "unknown"
    }
}
