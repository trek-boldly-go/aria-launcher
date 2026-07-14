// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aria.launcher.aria.chat.ToolExecutor
import com.aria.launcher.aria.chat.ToolResult
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.DomainPermissionDao
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
import com.aria.launcher.aria.llm.ToolCall
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val domainPermissionDao: DomainPermissionDao,
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
        Log.d(
            TAG,
            "Editorial context: " +
                variables.entries.joinToString(", ") { "${it.key}=${it.value.take(80)}" },
        )

        val notifContentEnabled = ariaPreferences.getNotificationContentEnabled()
        val agenticMode = ariaPreferences.getAgenticBriefEnabled()

        // Use tool-calling loop when skills, notification reading, or agentic mode are available
        if (heartbeatSkills.isNotEmpty() || notifContentEnabled || agenticMode) {
            return generateBriefWithTools(
                provider,
                systemPrompt,
                heartbeatSkills,
                notifContentEnabled,
                agenticMode,
            )
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
     *
     * In agentic mode, tools are tier-checked before execution:
     * - AUTO tools execute immediately and produce ActionReport cards
     * - CONFIRM tools are held and produce ConfirmationRequest cards
     * - NEVER tools are rejected (shouldn't be offered, but safety net)
     */
    private suspend fun generateBriefWithTools(
        provider: LlmProvider,
        systemPrompt: String,
        skills: List<AgentSkillEntry>,
        notificationContentEnabled: Boolean = false,
        agenticMode: Boolean = false,
    ): List<BriefItem>? {
        val toolExecutor = ToolExecutor(
            context = appContext,
            httpClient = httpClient,
            agentSkillManager = agentSkillManager,
            appLabelResolver = appLabelResolver,
            isNotificationContentEnabled = { notificationContentEnabled },
        )
        val skillNames = skills.map { it.name }
        val capabilities = if (agenticMode) capabilityCatalog.getCapabilities() else emptyList()
        val tools = AriaPrompts.buildEditorialTools(
            skillNames,
            notificationContentEnabled,
            agenticMode,
            capabilities,
        )
        val maxRounds = if (agenticMode) MAX_TOOL_ROUNDS_AGENTIC else MAX_TOOL_ROUNDS

        val executedActions = mutableListOf<ExecutedAction>()
        val pendingConfirmations = mutableListOf<PendingConfirmation>()

        var messages = listOf(ChatMessage(Role.USER, "Generate the Brief for this context."))
        var round = 0

        while (round < maxRounds) {
            val result = provider.completeWithTools(
                systemPrompt = systemPrompt,
                messages = messages,
                tools = tools,
                maxTokens = 1024,
            )

            when (result) {
                is LlmResult.Text -> {
                    Log.d(TAG, "Heartbeat LLM response (round $round):\n${result.content}")
                    val items = parseJsonToBriefItems(result.content)
                        ?: return agenticCardsOrNull(executedActions, pendingConfirmations)
                    return items + buildAgenticCards(executedActions, pendingConfirmations)
                }

                is LlmResult.ToolUse -> {
                    Log.d(
                        TAG,
                        "Heartbeat tool calls (round $round): " +
                            result.toolCalls.joinToString { it.name },
                    )
                    val toolMessages = result.toolCalls.map { toolCall ->
                        val resultText = processToolCall(
                            toolCall,
                            toolExecutor,
                            agenticMode,
                            executedActions,
                            pendingConfirmations,
                        )
                        ChatMessage(
                            role = Role.TOOL,
                            content = resultText,
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                        )
                    }
                    messages = messages +
                        ChatMessage(Role.ASSISTANT, result.content, toolCalls = result.toolCalls) +
                        toolMessages
                    round++
                }

                is LlmResult.Error -> {
                    Log.w(TAG, "Heartbeat LLM failed: ${result.message}")
                    applyBackoffIfRateLimited(result.message)
                    return agenticCardsOrNull(executedActions, pendingConfirmations)
                }
            }
        }

        Log.w(TAG, "Heartbeat exceeded max tool rounds ($maxRounds)")
        return agenticCardsOrNull(executedActions, pendingConfirmations)
    }

    /**
     * Returns the agentic cards for actions already executed or confirmations already
     * queued this run, or null if there are none. Used on the error / max-rounds /
     * unparseable-JSON exits so a real side effect or a pending user confirmation is
     * never silently dropped (which would leave the Brief falling back to heuristics
     * and the user never asked to approve the action the model believes it queued).
     */
    private fun agenticCardsOrNull(
        executedActions: List<ExecutedAction>,
        pendingConfirmations: List<PendingConfirmation>,
    ): List<BriefItem>? {
        if (executedActions.isEmpty() && pendingConfirmations.isEmpty()) return null
        return buildAgenticCards(executedActions, pendingConfirmations)
    }

    /**
     * Processes a single tool call through the permission tier system.
     * Returns the text to feed back to the LLM as the tool result.
     */
    private suspend fun processToolCall(
        toolCall: ToolCall,
        toolExecutor: ToolExecutor,
        agenticMode: Boolean,
        executedActions: MutableList<ExecutedAction>,
        pendingConfirmations: MutableList<PendingConfirmation>,
    ): String {
        if (!agenticMode) {
            val result = toolExecutor.execute(toolCall)
            return "[Tool ${result.toolName}]: ${result.result}"
        }

        val tier = resolveTier(toolCall)
        Log.d(TAG, "Tool ${toolCall.name} tier: $tier")

        return when (tier) {
            ToolPermissionTier.AUTO -> {
                val result = toolExecutor.execute(toolCall)
                if (result.success) {
                    executedActions.add(ExecutedAction(toolCall, result))
                }
                "[Tool ${result.toolName}]: ${result.result}"
            }

            ToolPermissionTier.CONFIRM -> {
                pendingConfirmations.add(PendingConfirmation(toolCall))
                "[Tool ${toolCall.name}]: Action held for user confirmation. " +
                    "The user will see an approval card on their home screen."
            }

            ToolPermissionTier.NEVER -> {
                "[Tool ${toolCall.name}]: This action is not available in the Brief."
            }
        }
    }

    /** Resolves the permission tier, with special handling for fetch_url domain checks. */
    private suspend fun resolveTier(toolCall: ToolCall): ToolPermissionTier {
        if (toolCall.name == "fetch_url") {
            val url = toolCall.arguments["url"]?.jsonPrimitive?.contentOrNull
                ?: return ToolPermissionTier.CONFIRM
            val method = toolCall.arguments["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
            return EditorialToolPolicy.tierForFetchUrl(url, method, domainPermissionDao)
        }
        return EditorialToolPolicy.tierFor(toolCall.name)
    }

    /** Constructs ActionReport and ConfirmationRequest cards from agentic execution results. */
    private fun buildAgenticCards(
        executedActions: List<ExecutedAction>,
        pendingConfirmations: List<PendingConfirmation>,
    ): List<BriefItem> {
        val cards = mutableListOf<BriefItem>()

        for (action in executedActions) {
            cards.add(
                BriefItem.ActionReport(
                    icon = iconForTool(action.toolCall.name),
                    headline = action.result.result.take(60),
                    subtext = null,
                    toolName = action.toolCall.name,
                    action = null,
                ),
            )
        }

        for (pending in pendingConfirmations) {
            val toolCallJson = json.encodeToString(serializeToolCall(pending.toolCall))
            val isFetchUrl = pending.toolCall.name == "fetch_url"
            val actions = mutableListOf(
                BriefAction(label = "Approve", intentUri = "aria://confirm/approve"),
            )
            if (isFetchUrl) {
                actions.add(BriefAction(label = "Always Allow", intentUri = "aria://confirm/always"))
            }
            actions.add(BriefAction(label = "Dismiss", intentUri = "aria://confirm/dismiss"))

            cards.add(
                BriefItem.ConfirmationRequest(
                    icon = iconForTool(pending.toolCall.name),
                    headline = describeToolCall(pending.toolCall),
                    subtext = "ARIA needs your OK",
                    toolName = pending.toolCall.name,
                    pendingToolCallJson = toolCallJson,
                    actions = actions,
                ),
            )
        }

        return cards
    }

    private fun serializeToolCall(toolCall: ToolCall): JsonObject {
        return kotlinx.serialization.json.buildJsonObject {
            put("id", JsonPrimitive(toolCall.id))
            put("name", JsonPrimitive(toolCall.name))
            put("arguments", JsonObject(toolCall.arguments))
        }
    }

    private fun describeToolCall(toolCall: ToolCall): String {
        val args = toolCall.arguments
        return when (toolCall.name) {
            "compose_message" -> {
                val contact = args["contact"]?.jsonPrimitive?.contentOrNull ?: "someone"
                "Text $contact?"
            }

            "send_email" -> {
                val to = args["to"]?.jsonPrimitive?.contentOrNull ?: "someone"
                "Email $to?"
            }

            "make_call" -> {
                val number = args["number"]?.jsonPrimitive?.contentOrNull ?: "someone"
                "Call $number?"
            }

            "create_event" -> {
                val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "event"
                "Create \"$title\"?"
            }

            "fetch_url" -> {
                val url = args["url"]?.jsonPrimitive?.contentOrNull ?: "a URL"
                val domain = try {
                    Uri.parse(url).host
                } catch (_: Exception) {
                    url
                }
                "Fetch from $domain?"
            }

            "share_text" -> "Share text?"

            else -> "${toolCall.name}?"
        }
    }

    private fun iconForTool(toolName: String): String = when (toolName) {
        "set_reminder" -> "alarm"
        "set_timer" -> "timer"
        "get_directions" -> "directions_car"
        "search_web" -> "search"
        "compose_message" -> "message"
        "send_email" -> "email"
        "make_call" -> "call"
        "create_event" -> "event"
        "fetch_url" -> "cloud_download"
        "share_text" -> "share"
        else -> "smart_toy"
    }

    private data class ExecutedAction(val toolCall: ToolCall, val result: ToolResult)
    private data class PendingConfirmation(val toolCall: ToolCall)

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

            "action_report" -> BriefItem.ActionReport(
                icon = icon,
                headline = headline,
                subtext = subtext,
                toolName = obj["tool_name"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                action = action,
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
        private const val MAX_TOOL_ROUNDS_AGENTIC = 5
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
            "action" to "action_report",
            "action_completed" to "action_report",
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
