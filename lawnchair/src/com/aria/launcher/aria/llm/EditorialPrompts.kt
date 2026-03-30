// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.llm

import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.AriaContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Builds editorial prompt variables and resolves templates for ARIA's LLM Brief curation.
 * The template itself is stored in AriaPreferences and editable by the user.
 */
object EditorialPrompts {

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    /**
     * Resolves a template string by replacing ${variable} placeholders with values.
     * Unknown placeholders are left as-is so user custom text is preserved.
     */
    fun resolveTemplate(
        template: String,
        variables: Map<String, String>,
    ): String {
        var result = template
        for ((key, value) in variables) {
            result = result.replace("\${$key}", value)
        }
        return result
    }

    /**
     * Builds the variable map from live context signals.
     * Each key corresponds to a ${variable} in the editorial prompt template.
     */
    fun buildVariables(
        context: AriaContext,
        weather: WeatherProvider.WeatherSnapshot? = null,
        capabilities: String = "",
        notifications: String = "",
        battery: String = "",
        typicalApps: String = "",
    ): Map<String, String> {
        val key = context.contextKey
        val now = context.timestampMs
        val formattedTime = timeFormat.format(Date(now))
        val activityStr = context.detectedActivity?.let { activityLabel(it) } ?: "unknown"

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

        val recentAppsStr = if (context.recentAppLabels.isEmpty()) {
            "none"
        } else {
            context.recentAppLabels.take(5).joinToString(", ")
        }

        val recentPackagesStr = if (context.recentAppPackages.isEmpty()) {
            ""
        } else {
            context.recentAppPackages.take(5).joinToString(", ") { "package:$it" }
        }

        val weatherStr = weather?.let {
            "${it.condition}, ${it.tempF}\u00B0F" +
                (if (it.feelsLikeF != it.tempF) " (feels like ${it.feelsLikeF}\u00B0)" else "") +
                if (it.windSpeedMph >= 15) ", wind ${it.windSpeedMph} mph" else ""
        } ?: "unavailable"

        val chargingStr = if (context.isCharging) "yes" else "no"
        val vehicleStr = when {
            context.isAndroidAutoConnected ->
                "connected to Android Auto" + (context.connectedCarName?.let { " ($it)" } ?: "")

            key.vehicleContext.name != "NONE" -> key.vehicleContext.name.lowercase().replace('_', ' ')

            else -> "no"
        }

        val visitStr = context.visitContext ?: "unknown"

        val rulesStr = if (context.firedRules.isEmpty()) {
            "none"
        } else {
            context.firedRules.joinToString("; ") { "rule#${it.ruleId}(${it.action::class.simpleName})" }
        }

        return mapOf(
            "time" to formattedTime,
            "time_bucket" to key.timeBucket.name,
            "day_type" to key.dayType.name,
            "location" to key.location.name,
            "activity" to activityStr,
            "charging" to chargingStr,
            "vehicle" to vehicleStr,
            "weather" to weatherStr,
            "visit_context" to visitStr,
            "calendar" to eventsStr,
            "recent_apps" to recentAppsStr,
            "recent_packages" to recentPackagesStr,
            "venue" to (context.currentVenueCategory ?: "unknown"),
            "rules" to rulesStr,
            "capabilities" to capabilities,
            "notifications" to notifications.ifEmpty { "none" },
            "battery" to battery.ifEmpty {
                val level = context.batteryLevel
                if (level >= 0) "$level%${if (context.isCharging) " (charging)" else ""}" else "unknown"
            },
            "typical_apps" to typicalApps.ifEmpty { "unknown" },
        )
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
