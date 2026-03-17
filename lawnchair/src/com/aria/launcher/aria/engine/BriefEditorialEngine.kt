// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.util.Log
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
) {
    /**
     * Generates a curated Brief via LLM editorial.
     * Returns null if no provider is configured or if the LLM call fails.
     */
    suspend fun generateBrief(context: AriaContext): List<BriefItem>? {
        val provider = llmProviderManager.getProvider() ?: return null

        val systemPrompt = EditorialPrompts.buildEditorialSystemPrompt(context)
        val result = provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(ChatMessage(Role.USER, "Generate the Brief for this context.")),
            maxTokens = 1024,
        )

        return when (result) {
            is LlmResult.Text -> parseJsonToBriefItems(result.content)

            is LlmResult.Error -> {
                Log.w(TAG, "LLM editorial failed: ${result.message}")
                null
            }

            else -> null
        }
    }

    private fun parseJsonToBriefItems(jsonText: String): List<BriefItem>? {
        return try {
            val root = json.parseToJsonElement(jsonText.trim()).jsonObject
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
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val icon = obj["icon"]?.jsonPrimitive?.contentOrNull ?: "info"
        val headline = obj["headline"]?.jsonPrimitive?.contentOrNull ?: return null
        val subtext = obj["subtext"]?.jsonPrimitive?.contentOrNull
        val action = parseAction(obj["action"]?.jsonObject)

        return when (type) {
            "alert_assessed" -> BriefItem.AlertAssessed(
                icon = icon,
                headline = headline,
                subtext = subtext,
                severity = AlertSeverity.INFO,
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
        val intentUri = obj["intentUri"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }
        return BriefAction(label = label, intentUri = intentUri)
    }

    companion object {
        private const val TAG = "ARIA.EditorialEngine"
    }
}
