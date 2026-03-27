// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.util.Log
import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.EditorialPrompts
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Calls the LLM once per context change, parses the JSON response, and returns
 * a curated [List<BriefItem>] for the home screen Brief.
 *
 * Falls back to null if no LLM is configured — [BriefAggregator] then falls
 * back to the heuristic source-based ranking path.
 */
@Singleton
class BriefEditorialEngine @Inject constructor(
    private val llmProviderManager: LlmProviderManager,
    private val json: Json,
    private val weatherProvider: WeatherProvider,
) {
    /**
     * Generates a curated Brief via LLM editorial.
     * Returns null if no provider is configured or if the LLM call fails.
     */
    suspend fun generateBrief(context: AriaContext): List<BriefItem>? {
        val provider = llmProviderManager.getProvider() ?: return null

        val weather = weatherProvider.getWeather()
        val systemPrompt = EditorialPrompts.buildEditorialSystemPrompt(context, weather)
        val result = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(ChatMessage(Role.USER, "Generate the Brief for this context.")),
            maxTokens = 1024,
        )

        return when (result) {
            is LlmResult.Text -> {
                Log.d(TAG, "LLM response:\n${result.content}")
                parseJsonToBriefItems(result.content)
            }

            is LlmResult.Error -> {
                Log.w(TAG, "LLM editorial failed: ${result.message}")
                null
            }

            else -> null
        }
    }

    private fun parseJsonToBriefItems(jsonText: String): List<BriefItem>? {
        return try {
            val cleaned = stripMarkdownFences(jsonText)
            val root = json.parseToJsonElement(cleaned).jsonObject
            val briefArray = root["brief"]?.jsonArray ?: return emptyList()
            briefArray.mapNotNull { element ->
                runCatching { parseBriefItem(element.jsonObject) }.getOrNull()
            }.take(5)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse editorial JSON", e)
            null
        }
    }

    private fun parseBriefItem(obj: JsonObject): BriefItem? {
        val rawType = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val type = normalizeType(rawType)
        val icon = obj["icon"]?.jsonPrimitive?.contentOrNull ?: "info"
        val headline = obj["headline"]?.jsonPrimitive?.contentOrNull ?: return null
        val subtext = obj["subtext"]?.jsonPrimitive?.contentOrNull
        val rawAction = parseAction(obj["action"]?.jsonObject)
        val action = rawAction?.withFallbackIntent(type, icon)
        if (type != rawType) {
            Log.d(TAG, "Type normalized: '$rawType' → '$type'")
        }
        Log.d(TAG, "Parsed item: type=$type icon=$icon headline=$headline " +
            "rawIntent=${rawAction?.intentUri} finalIntent=${action?.intentUri}")

        return when (type) {
            "alert_assessed" -> BriefItem.AlertAssessed(
                icon = icon,
                headline = headline,
                subtext = subtext,
                severity = parseSeverity(obj["severity"]?.jsonPrimitive?.contentOrNull),
                action = action,
            )

            "reminder_nudge" -> BriefItem.ReminderNudge(
                icon = icon,
                headline = headline,
                subtext = subtext,
                action = action,
            )

            "calendar_event" -> BriefItem.CalendarEvent(
                title = headline,
                timeDescription = subtext ?: "",
                location = null,
                primaryAction = action ?: BriefAction("Open", null),
            )

            "media_resume" -> BriefItem.MediaResume(
                title = headline,
                subtitle = subtext ?: "",
                thumbnailUri = null,
                resumeAction = action ?: BriefAction("Resume", null),
            )

            "proactive_suggestion" -> BriefItem.ProactiveSuggestion(
                headline = headline,
                rationale = subtext ?: "",
                action = action ?: BriefAction("OK", null),
            )

            "venue_card" -> BriefItem.VenueCard(
                venueName = headline,
                venueCategory = icon,
                headline = headline,
                actions = listOfNotNull(action),
            )

            "live_data_card" -> BriefItem.LiveDataCard(
                sourceId = "llm_editorial",
                icon = icon,
                headline = headline,
                subtext = subtext,
                action = action,
                refreshedAt = System.currentTimeMillis(),
            )

            else -> null
        }
    }

    private fun parseAction(obj: JsonObject?): BriefAction? {
        obj ?: return null
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: return null
        val raw = obj["intentUri"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }
        val intentUri = sanitizeIntentUri(raw)
        return BriefAction(label = label, intentUri = intentUri)
    }

    /**
     * Clean up LLM-generated intentUri values:
     * - Reject known placeholders (com.example.*)
     * - Prepend "package:" if it looks like a package name but is missing the prefix
     */
    private fun sanitizeIntentUri(uri: String?): String? {
        uri ?: return null
        if (uri.startsWith("com.example.")) return null
        if (uri.startsWith("package:") || uri.startsWith("intent:")) return uri
        // Looks like a bare package name (has dots, no spaces, no colons)
        if ('.' in uri && ' ' !in uri && ':' !in uri) {
            Log.d(TAG, "Prepending 'package:' to bare intentUri: $uri")
            return "package:$uri"
        }
        return uri
    }

    private fun parseSeverity(value: String?): AlertSeverity = when (value?.lowercase()) {
        "critical" -> AlertSeverity.CRITICAL
        "warning" -> AlertSeverity.WARNING
        else -> AlertSeverity.INFO
    }

    /**
     * Small models often return abbreviated or invented type names.
     * Map common mistakes to valid BriefItem types.
     */
    private fun normalizeType(raw: String): String = TYPE_ALIASES[raw.lowercase()] ?: raw

    /**
     * LLMs often omit or guess intentUri. For known card types, fill in
     * the correct Android package URI so buttons actually work.
     */
    private fun BriefAction.withFallbackIntent(type: String, icon: String): BriefAction {
        // Apply fallback if intentUri is null or doesn't look like a valid scheme
        if (intentUri != null && (intentUri.startsWith("package:") || intentUri.startsWith("intent:"))) {
            return this
        }
        val fallbackUri = INTENT_FALLBACKS[icon.lowercase()] ?: INTENT_FALLBACKS[type]
        return if (fallbackUri != null) copy(intentUri = fallbackUri) else this
    }

    /** Strip ```json ... ``` fences that small models tend to wrap around JSON output. */
    private fun stripMarkdownFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val start = trimmed.indexOf('\n')
        if (start == -1) return trimmed
        val end = trimmed.lastIndexOf("```")
        if (end <= start) return trimmed.substring(start + 1).trim()
        return trimmed.substring(start + 1, end).trim()
    }

    companion object {
        private const val TAG = "ARIA.EditorialEngine"

        /** Map abbreviated/invented type names to valid BriefItem types. */
        private val TYPE_ALIASES = mapOf(
            "info" to "live_data_card",
            "weather" to "live_data_card",
            "live_data" to "live_data_card",
            "alert" to "alert_assessed",
            "warning" to "alert_assessed",
            "suggestion" to "proactive_suggestion",
            "proactive" to "proactive_suggestion",
            "reminder" to "reminder_nudge",
            "calendar" to "calendar_event",
            "media" to "media_resume",
            "venue" to "venue_card",
        )

        /** Fallback intent URIs for common LLM-generated card types/icons. */
        private val INTENT_FALLBACKS = mapOf(
            // Icon-based (LLM often uses material icon names)
            // Weather: try multiple known packages via WEATHER_PACKAGES in AriaHomeState
            "weather" to "package:weather",
            "storm" to "package:weather",
            "cloud" to "package:weather",
            "rainy" to "package:weather",
            "thunderstorm" to "package:weather",
            "flood" to "package:weather",
            "calendar_today" to "package:com.google.android.calendar",
            "event" to "package:com.google.android.calendar",
            "directions_car" to "package:com.google.android.apps.maps",
            "map" to "package:com.google.android.apps.maps",
            "navigation" to "package:com.google.android.apps.maps",
            // Type-based fallback
            "alert_assessed" to "package:weather",
        )
    }
}
