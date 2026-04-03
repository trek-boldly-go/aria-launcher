// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import android.content.Context
import android.util.Log
import com.aria.launcher.aria.chat.ToolExecutor
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.skills.AgentSkillEntry
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import com.aria.launcher.aria.llm.AriaLlmClient
import com.aria.launcher.aria.llm.AriaPrompts
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.EditorialPrompts
import com.aria.launcher.aria.llm.LlmProvider
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

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
    private val ariaPreferences: AriaPreferences,
    private val capabilityCatalog: DeviceCapabilityCatalog,
    private val usageDataRepository: UsageDataRepository,
    private val appLabelResolver: AppLabelResolver,
    private val appActivityCatalog: AppActivityCatalog,
    private val agentSkillManager: AgentSkillManager,
    @AriaLlmClient private val httpClient: OkHttpClient,
    @ApplicationContext private val appContext: Context,
) {
    private var lastCallTimestamp = 0L
    private var rateLimitBackoffUntil = 0L

    /**
     * Generates a curated Brief via LLM editorial.
     * Returns null if no provider is configured or if the LLM call fails.
     * Enforces a minimum cooldown between LLM calls and backs off on rate limits.
     */
    suspend fun generateBrief(context: AriaContext): List<BriefItem>? {
        val provider = llmProviderManager.getProvider() ?: return null

        val now = System.currentTimeMillis()
        if (now < rateLimitBackoffUntil) {
            Log.d(TAG, "Rate-limit backoff active, ${(rateLimitBackoffUntil - now) / 1000}s remaining")
            return null
        }
        if (now - lastCallTimestamp < MIN_CALL_INTERVAL_MS) {
            Log.d(TAG, "Cooldown active, skipping LLM call")
            return null
        }
        lastCallTimestamp = now

        val weather = weatherProvider.getWeather()
        val capabilities = capabilityCatalog.getCapabilitySummaryForPrompt()
        val notifications = buildNotificationSummary()
        val typicalApps = buildTypicalAppsSummary(context)
        val appActivities = buildActivitySummary(context)

        // Build skill catalog for heartbeat injection
        val heartbeatSkills = agentSkillManager.getHeartbeatSkills()
        val skillCatalogText = if (heartbeatSkills.isNotEmpty()) {
            heartbeatSkills.joinToString("\n") { "- ${it.name}: ${it.description}" }
        } else {
            ""
        }

        val template = ariaPreferences.getEditorialPromptTemplate()
        val variables = EditorialPrompts.buildVariables(
            context = context,
            weather = weather,
            capabilities = capabilities,
            notifications = notifications,
            typicalApps = typicalApps,
            appActivities = appActivities,
            skills = skillCatalogText,
        )
        val systemPrompt = EditorialPrompts.resolveTemplate(template, variables)

        val notifContentEnabled = ariaPreferences.getNotificationContentEnabled()

        // Use tool-calling loop when skills or notification reading are available
        if (heartbeatSkills.isNotEmpty() || notifContentEnabled) {
            return generateBriefWithTools(provider, systemPrompt, heartbeatSkills, notifContentEnabled)
        }

        // Fallback: no skills and no notification content tool, simple completion
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
                applyBackoffIfRateLimited(result.message)
                null
            }

            else -> null
        }
    }

    /**
     * Heartbeat path: uses completeWithTools() so the LLM can call fetch_url
     * and activate_skill to bring external data into the Brief.
     */
    private suspend fun generateBriefWithTools(
        provider: LlmProvider,
        systemPrompt: String,
        skills: List<AgentSkillEntry>,
        notificationContentEnabled: Boolean = false,
    ): List<BriefItem>? {
        val toolExecutor = ToolExecutor(
            context = appContext,
            httpClient = httpClient,
            agentSkillManager = agentSkillManager,
            appLabelResolver = appLabelResolver,
            isNotificationContentEnabled = { notificationContentEnabled },
        )
        val skillNames = skills.map { it.name }
        val tools = AriaPrompts.buildEditorialTools(skillNames, notificationContentEnabled)

        var messages = listOf(ChatMessage(Role.USER, "Generate the Brief for this context."))
        var round = 0

        while (round < MAX_TOOL_ROUNDS) {
            val result = provider.completeWithTools(
                systemPrompt = systemPrompt,
                messages = messages,
                tools = tools,
                maxTokens = 1024,
            )

            when (result) {
                is LlmResult.Text -> {
                    Log.d(TAG, "Heartbeat LLM response (round $round):\n${result.content}")
                    return parseJsonToBriefItems(result.content)
                }

                is LlmResult.ToolUse -> {
                    Log.d(
                        TAG,
                        "Heartbeat tool calls (round $round): " +
                            result.toolCalls.joinToString { it.name },
                    )
                    val toolResults = result.toolCalls.map { toolCall ->
                        toolExecutor.execute(toolCall)
                    }
                    val toolResultText = toolResults.joinToString("\n") {
                        "[Tool ${it.toolName}]: ${it.result}"
                    }
                    messages = messages + listOf(
                        ChatMessage(Role.ASSISTANT, result.content),
                        ChatMessage(Role.USER, toolResultText),
                    )
                    round++
                }

                is LlmResult.Error -> {
                    Log.w(TAG, "Heartbeat LLM failed: ${result.message}")
                    applyBackoffIfRateLimited(result.message)
                    return null
                }
            }
        }

        Log.w(TAG, "Heartbeat exceeded max tool rounds ($MAX_TOOL_ROUNDS)")
        return null
    }

    private fun parseJsonToBriefItems(jsonText: String): List<BriefItem>? {
        return try {
            val cleaned = stripMarkdownFences(jsonText)
            val root = json.parseToJsonElement(cleaned).jsonObject
            val briefArray = root["brief"]?.jsonArray ?: return emptyList()
            briefArray.mapNotNull { element ->
                runCatching { parseBriefItem(element.jsonObject) }.getOrNull()
            }
                .filter { !isWeatherCard(it) }
                .take(5)
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
        Log.d(
            TAG,
            "Parsed item: type=$type icon=$icon headline=$headline " +
                "rawIntent=${rawAction?.intentUri} finalIntent=${action?.intentUri}",
        )

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

    /**
     * Gets the top predicted apps for the current context key — these are
     * apps the user typically opens at this time/day/location.
     */
    private suspend fun buildTypicalAppsSummary(context: AriaContext): String {
        val contextKey = context.contextKey.toStringKey()
        val predictions = usageDataRepository.getTopApps(contextKey, limit = 5)
        if (predictions.isEmpty()) return ""
        return predictions.joinToString(", ") { appLabelResolver.resolve(it.packageName) }
    }

    /**
     * Builds a compact summary of discoverable app screens for the editorial prompt.
     * Only includes apps the user typically opens in this context.
     */
    private suspend fun buildActivitySummary(context: AriaContext): String {
        val packages = context.recentAppPackages.take(10)
        if (packages.isEmpty()) return ""
        return appActivityCatalog.getPromptSummary(packages, maxPerApp = 3)
    }

    /**
     * Builds a compact notification summary for the editorial prompt.
     * Groups by package and shows count + latest title.
     */
    private fun buildNotificationSummary(): String {
        val notifications = AriaNotificationListener.getNotifications()
        if (notifications.isEmpty()) return ""
        return notifications
            .groupBy { it.packageName }
            .entries
            .sortedByDescending { it.value.size }
            .take(5)
            .joinToString(", ") { (pkg, items) ->
                val label = appLabelResolver.resolve(pkg)
                val latest = items.maxByOrNull { it.postedTime }?.title
                if (latest != null && items.size > 1) {
                    "$label (${items.size}, latest: $latest)"
                } else if (latest != null) {
                    "$label ($latest)"
                } else {
                    "$label (${items.size})"
                }
            }
    }

    private fun applyBackoffIfRateLimited(message: String) {
        if ("429" in message || "rate_limit" in message.lowercase()) {
            rateLimitBackoffUntil = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
            Log.w(TAG, "Rate limited — backing off for ${RATE_LIMIT_BACKOFF_MS / 1000}s")
        }
    }

    companion object {
        private const val TAG = "ARIA.EditorialEngine"
        private const val MAX_TOOL_ROUNDS = 3
        private const val MIN_CALL_INTERVAL_MS = 60_000L
        private const val RATE_LIMIT_BACKOFF_MS = 120_000L

        private val WEATHER_ICONS = setOf(
            "cloud", "rainy", "storm", "thunderstorm", "weather", "sunny",
            "snow", "foggy", "partly_cloudy", "cloudy", "ac_unit", "wb_sunny",
            "umbrella", "water_drop", "thermostat", "air",
        )

        /** Safety-net filter: drop weather-themed LiveDataCards since ContextBar handles weather. */
        private fun isWeatherCard(item: BriefItem): Boolean {
            if (item !is BriefItem.LiveDataCard) return false
            if (item.icon.lowercase() in WEATHER_ICONS) {
                Log.d(TAG, "Filtered weather card: icon=${item.icon} headline=${item.headline}")
                return true
            }
            return false
        }

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
