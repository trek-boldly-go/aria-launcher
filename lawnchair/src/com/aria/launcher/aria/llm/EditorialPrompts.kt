// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.llm

import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.AriaContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Builds the editorial system prompt for ARIA's LLM Brief curation.
 * Called once per context change, not per unlock.
 */
object EditorialPrompts {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    fun buildEditorialSystemPrompt(
        context: AriaContext,
        weather: WeatherProvider.WeatherSnapshot? = null,
    ): String {
        val key = context.contextKey
        val now = context.timestampMs
        val formattedTime = timeFormat.format(Date(now))
        val activityStr = context.detectedActivity?.let { activityLabel(it) } ?: "unknown"

        // Calendar: include time-until and start time, not just titles
        val eventsStr = if (context.upcomingEvents.isEmpty()) {
            "none"
        } else {
            context.upcomingEvents.joinToString("; ") { event ->
                val minutesUntil = TimeUnit.MILLISECONDS.toMinutes(event.startTimeMs - now)
                val eventTime = timeFormat.format(Date(event.startTimeMs))
                if (event.isAllDay) {
                    "${event.title} (all day)"
                } else if (minutesUntil <= 0) {
                    "${event.title} (happening now, started at $eventTime)"
                } else {
                    "${event.title} (at $eventTime, in ${formatDuration(minutesUntil)})"
                }
            }
        }

        // Apps: use labels when available, fall back to package name
        val recentAppsStr = if (context.recentAppLabels.isEmpty()) {
            "none"
        } else {
            context.recentAppLabels.take(5).joinToString(", ")
        }

        // Weather
        val weatherStr = weather?.let {
            "${it.condition}, ${it.tempF}\u00B0F" +
                (if (it.feelsLikeF != it.tempF) " (feels like ${it.feelsLikeF}\u00B0)" else "") +
                if (it.windSpeedMph >= 15) ", wind ${it.windSpeedMph} mph" else ""
        } ?: "unavailable"

        // Device state
        val chargingStr = if (context.isCharging) "yes" else "no"
        val vehicleStr = when {
            context.isAndroidAutoConnected ->
                "connected to Android Auto" + (context.connectedCarName?.let { " ($it)" } ?: "")

            key.vehicleContext.name != "NONE" -> key.vehicleContext.name.lowercase().replace('_', ' ')

            else -> "no"
        }

        // Visit context
        val visitStr = context.visitContext ?: "unknown"

        // Rules
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
            - Charging: $chargingStr
            - In vehicle: $vehicleStr
            - Weather: $weatherStr
            - Visit context: $visitStr
            - Upcoming calendar events: $eventsStr
            - Recently used apps: $recentAppsStr
            - Current venue: ${context.currentVenueCategory ?: "unknown"}
            - Active user rules that fired: $rulesStr

            Respond ONLY with valid JSON. No markdown fences, no explanation, no preamble.

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

    private fun formatDuration(minutes: Long): String = when {
        minutes < 60 -> "${minutes}min"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}min"
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
