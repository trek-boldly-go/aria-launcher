// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.chat

import android.content.Context
import android.util.Log
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.ContactsRepository
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.LocationProvider
import com.aria.launcher.aria.data.MemoryRepository
import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.AppActivityCatalog
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import com.aria.launcher.aria.llm.AriaPrompts
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.LlmProvider
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import com.aria.launcher.aria.llm.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class UiMessage(
    val role: Role,
    val content: String,
    val toolResults: List<ToolResult> = emptyList(),
    val suggestedReplies: List<String> = emptyList(),
    val confirmationAction: ConfirmationAction? = null,
)

class ChatState(
    private val context: Context,
    private val llmProviderManager: LlmProviderManager,
    private val contextSignalManager: ContextSignalManager,
    private val memoryRepo: MemoryRepository,
    private val ariaPreferences: AriaPreferences,
    private val ariaChatHandler: AriaChatHandler,
    private val capabilityCatalog: DeviceCapabilityCatalog,
    private val appActivityCatalog: AppActivityCatalog,
    private val httpClient: OkHttpClient,
    private val agentSkillManager: AgentSkillManager,
    private val appLabelResolver: AppLabelResolver,
    private val contactsRepository: ContactsRepository,
    private val calendarEventProvider: CalendarEventProvider,
    private val weatherProvider: WeatherProvider,
    private val locationProvider: LocationProvider,
) {
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Pending confirmation action waiting for user to say "Yes" or "No". */
    private var pendingConfirmation: ConfirmationAction? = null

    private val toolExecutor = ToolExecutor(
        context = context,
        httpClient = httpClient,
        agentSkillManager = agentSkillManager,
        appLabelResolver = appLabelResolver,
        contactsRepository = contactsRepository,
        calendarEventProvider = calendarEventProvider,
        weatherProvider = weatherProvider,
        locationProvider = locationProvider,
        memoryRepository = memoryRepo,
        appActivityCatalog = appActivityCatalog,
        isNotificationContentEnabled = {
            runBlocking { ariaPreferences.notificationContentEnabled.first() }
        },
        isContactsAccessEnabled = {
            runBlocking { ariaPreferences.contactsAccessEnabled.first() }
        },
        isCalendarAccessEnabled = {
            runBlocking { ariaPreferences.calendarAccessEnabled.first() }
        },
        isLocationAccessEnabled = {
            runBlocking { ariaPreferences.locationAccessEnabled.first() }
        },
        startForgetConfirmation = { memory ->
            pendingConfirmation = ConfirmationAction.ForgetMemory(memory.id, memory.fact)
        },
    )

    /**
     * Called when the chat sheet is opened. Checks if the conversation has been idle
     * longer than the configured timeout and clears it if so.
     */
    suspend fun onChatOpened() {
        val timeoutMinutes = ariaPreferences.chatTimeoutMinutes.first()
        if (timeoutMinutes <= 0) return // 0 = never auto-clear

        val lastInteraction = ariaPreferences.chatLastInteraction.first()
        if (lastInteraction > 0) {
            val elapsed = System.currentTimeMillis() - lastInteraction
            val timeoutMs = timeoutMinutes * 60_000L
            if (elapsed > timeoutMs) {
                Log.d(TAG, "Chat idle for ${elapsed / 60_000}m (timeout: ${timeoutMinutes}m), clearing")
                clearMessages()
            }
        }
        ariaPreferences.setChatLastInteraction()
    }

    suspend fun sendMessage(text: String) {
        _error.value = null
        val userMessage = UiMessage(Role.USER, text)
        _messages.value += userMessage
        ariaPreferences.setChatLastInteraction()

        _isGenerating.value = true

        try {
            // If there's a pending confirmation, handle yes/no before hitting the LLM.
            val pendingAction = pendingConfirmation
            if (pendingAction != null) {
                val lowerText = text.trim().lowercase()
                // Affirmative phrasing is action-specific: "save it" confirms only a
                // rule save, "forget it" confirms only a memory deletion. Otherwise
                // "forget it" (a user discarding a proposed rule) would be read as
                // approval and save the rule.
                val isAffirmative = lowerText.startsWith("yes") || lowerText == "y" ||
                    (pendingAction is ConfirmationAction.SaveRule && lowerText.contains("save it")) ||
                    (pendingAction is ConfirmationAction.ForgetMemory && lowerText.contains("forget it"))
                val isNegative = lowerText.startsWith("no") || lowerText == "n" ||
                    (pendingAction is ConfirmationAction.SaveRule && lowerText.contains("forget it"))
                if (isAffirmative) {
                    pendingConfirmation = null
                    when (pendingAction) {
                        is ConfirmationAction.SaveRule -> {
                            val response = ariaChatHandler.confirmSaveRule(pendingAction.rule)
                            _messages.value += UiMessage(Role.ASSISTANT, response.text)
                        }

                        is ConfirmationAction.ForgetMemory -> {
                            memoryRepo.delete(pendingAction.memoryId)
                            _messages.value += UiMessage(
                                Role.ASSISTANT,
                                "Forgot: “${pendingAction.fact}”",
                            )
                        }
                    }
                    return
                } else if (isNegative) {
                    pendingConfirmation = null
                    val cancelText = when (pendingAction) {
                        is ConfirmationAction.SaveRule -> "Got it, rule discarded."
                        is ConfirmationAction.ForgetMemory -> "OK, keeping that memory."
                    }
                    _messages.value += UiMessage(Role.ASSISTANT, cancelText)
                    return
                }
                // "Change it" or anything else falls through to the LLM
                pendingConfirmation = null
            }

            // Check for rule creation or management intents before going to the LLM.
            if (ariaChatHandler.isShowRulesIntent(text)) {
                val response = ariaChatHandler.handleShowRules()
                _messages.value += UiMessage(Role.ASSISTANT, response.text)
                return
            }

            if (ariaChatHandler.isRuleCreationIntent(text)) {
                val response = ariaChatHandler.handleRuleCreation(text)
                pendingConfirmation = response.confirmationAction
                _messages.value += UiMessage(
                    role = Role.ASSISTANT,
                    content = response.text,
                    suggestedReplies = response.suggestedReplies,
                    confirmationAction = response.confirmationAction,
                )
                return
            }

            val provider = llmProviderManager.getProvider()
            if (provider == null) {
                _error.value = "No AI provider configured. Set one up in ARIA settings."
                return
            }

            val contextKey = ContextKey.current(
                wifiSsid = contextSignalManager.wifiSsid.value,
                detectedActivity = contextSignalManager.detectedActivity.value,
                homeWifiSsid = null,
                workWifiSsid = null,
                isAndroidAutoConnected = contextSignalManager.isAndroidAutoConnected.value,
            )

            // Load user memories most relevant to the current message
            val memories = memoryRepo.recentForContext(text, limit = 15)

            val capabilities = withContext(Dispatchers.IO) {
                capabilityCatalog.getCapabilities()
            }
            val capabilitySummary = capabilityCatalog.getCapabilitySummaryForPrompt()

            // Build activity summary for apps the user has
            val topPackages = capabilities
                .map { it.packageName }
                .distinct()
                .take(10)
            val activitySummary = withContext(Dispatchers.IO) {
                appActivityCatalog.getPromptSummary(topPackages, maxPerApp = 3)
            }

            val skillCatalog = withContext(Dispatchers.IO) {
                agentSkillManager.getSkillCatalog()
            }
            val skillNames = skillCatalog.map { it.name }

            val skillCatalogSummary = if (skillCatalog.isNotEmpty()) {
                "Available skills (call activate_skill to load instructions before using):\n" +
                    skillCatalog.joinToString("\n") { "- ${it.name}: ${it.description}" }
            } else {
                ""
            }

            val systemPrompt = AriaPrompts.buildSystemPrompt(
                signals = contextSignalManager,
                contextKey = contextKey,
                userMemories = memories.map { it.fact },
                capabilitySummary = capabilitySummary,
                activitySummary = activitySummary,
                skillCatalog = skillCatalogSummary,
            )

            val notifContentEnabled = ariaPreferences.getNotificationContentEnabled()
            val allTools = AriaPrompts.buildTools(capabilities, skillNames, notifContentEnabled)
            val tools = routeTools(provider, text, allTools)

            val chatMessages = _messages.value.map { msg ->
                ChatMessage(role = msg.role, content = msg.content)
            }

            var round = 0
            var currentMessages = chatMessages
            var repliedOrErrored = false

            while (round < MAX_TOOL_ROUNDS) {
                val result = withContext(Dispatchers.IO) {
                    provider.completeWithTools(
                        systemPrompt = systemPrompt,
                        messages = currentMessages,
                        tools = tools,
                    )
                }

                when (result) {
                    is LlmResult.Text -> {
                        val assistantMessage = UiMessage(Role.ASSISTANT, result.content)
                        _messages.value += assistantMessage
                        repliedOrErrored = true
                        break
                    }

                    is LlmResult.ToolUse -> {
                        // Show any text content from the assistant
                        if (result.content.isNotBlank()) {
                            val textMsg = UiMessage(Role.ASSISTANT, result.content)
                            _messages.value += textMsg
                        }

                        // Execute each tool call on IO dispatcher (some do sync HTTP)
                        val toolResults = withContext(Dispatchers.IO) {
                            result.toolCalls.map { toolCall ->
                                toolExecutor.execute(toolCall)
                            }
                        }

                        // Tool results are intermediate LLM context — don't show to user.
                        // Only show errors for tools that launch visible actions (not data-fetching).
                        val actionFailures = toolResults.filter {
                            !it.success && it.toolName !in INTERNAL_TOOLS
                        }
                        if (actionFailures.isNotEmpty()) {
                            val errorMsg = actionFailures.joinToString("\n") {
                                "${it.toolName}: ${it.result}"
                            }
                            _messages.value += UiMessage(Role.ASSISTANT, errorMsg)
                        }

                        // Feed results back to the AI for continuation using the correct
                        // protocol: the assistant message carries its structured tool_calls,
                        // and each result returns as its own TOOL message tied to a call id.
                        val assistantMessage = ChatMessage(
                            role = Role.ASSISTANT,
                            content = result.content,
                            toolCalls = result.toolCalls,
                        )
                        val toolMessages = result.toolCalls.mapIndexed { index, toolCall ->
                            val toolResult = toolResults[index]
                            ChatMessage(
                                role = Role.TOOL,
                                content = toolResult.result,
                                toolCallId = toolCall.id,
                                toolName = toolCall.name,
                            )
                        }
                        currentMessages = currentMessages + assistantMessage + toolMessages
                        round++
                    }

                    is LlmResult.Error -> {
                        _error.value = result.message
                        Log.e(TAG, "LLM error: ${result.message}", result.cause)
                        repliedOrErrored = true
                        break
                    }
                }
            }

            // If the model kept calling tools until the round budget ran out, it never
            // produced a final answer. Small models are especially prone to this — emit
            // a fallback so the user isn't left with a silent, stalled spinner.
            if (!repliedOrErrored) {
                Log.w(TAG, "Tool loop exhausted $MAX_TOOL_ROUNDS rounds without a final reply")
                _messages.value += UiMessage(
                    Role.ASSISTANT,
                    "I got stuck working through that one. Could you rephrase or try again?",
                )
            }

            // After conversation turn completes, extract memories in background
            extractMemories()
        } catch (e: Exception) {
            _error.value = "Failed to get response: ${e.message}"
            Log.e(TAG, "Chat failed", e)
        } finally {
            _isGenerating.value = false
        }
    }

    /**
     * Small-model tool budget: a cheap router pre-pass asks the model which tool
     * groups the user's turn might need, then trims [allTools] to those groups (plus
     * the always-on floor). Skipped when the set is already small. Any failure —
     * router error, non-text reply, or an empty result — falls back to all tools,
     * preserving the pre-Phase-4 behavior.
     */
    private suspend fun routeTools(
        provider: LlmProvider,
        userMessage: String,
        allTools: List<ToolDefinition>,
    ): List<ToolDefinition> {
        if (allTools.size <= ROUTER_TOOL_THRESHOLD) return allTools
        val reply = try {
            val result = withContext(Dispatchers.IO) {
                provider.complete(
                    systemPrompt = AriaPrompts.toolRouterSystemPrompt(),
                    messages = listOf(ChatMessage(Role.USER, userMessage)),
                    maxTokens = 64,
                )
            }
            (result as? LlmResult.Text)?.content
        } catch (e: Exception) {
            Log.w(TAG, "Tool router failed, offering all tools", e)
            null
        }
        val tools = AriaPrompts.toolsForRouterReply(reply, allTools)
        if (tools.size < allTools.size) {
            Log.d(TAG, "Tool router trimmed ${allTools.size} tools to ${tools.size}")
        }
        return tools
    }

    suspend fun sendMessageStreaming(text: String) {
        _error.value = null
        val userMessage = UiMessage(Role.USER, text)
        _messages.value += userMessage
        ariaPreferences.setChatLastInteraction()

        val provider = llmProviderManager.getProvider()
        if (provider == null) {
            _error.value = "No AI provider configured. Set one up in ARIA settings."
            return
        }

        _isGenerating.value = true

        try {
            val contextKey = ContextKey.current(
                wifiSsid = contextSignalManager.wifiSsid.value,
                detectedActivity = contextSignalManager.detectedActivity.value,
                homeWifiSsid = null,
                workWifiSsid = null,
                isAndroidAutoConnected = contextSignalManager.isAndroidAutoConnected.value,
            )

            val memories = memoryRepo.recentForContext(text, limit = 15)

            val systemPrompt = AriaPrompts.buildSystemPrompt(
                signals = contextSignalManager,
                contextKey = contextKey,
                userMemories = memories.map { it.fact },
            )

            val chatMessages = _messages.value.map { msg ->
                ChatMessage(role = msg.role, content = msg.content)
            }

            val streamingMessage = UiMessage(Role.ASSISTANT, "")
            _messages.value += streamingMessage
            val messageIndex = _messages.value.size - 1

            val stream = provider.streamComplete(
                systemPrompt = systemPrompt,
                messages = chatMessages,
            )

            val contentBuilder = StringBuilder()
            stream.collect { chunk ->
                contentBuilder.append(chunk)
                val updatedMessages = _messages.value.toMutableList()
                updatedMessages[messageIndex] = UiMessage(Role.ASSISTANT, contentBuilder.toString())
                _messages.value = updatedMessages
            }

            // Extract memories after streaming completes
            extractMemories()
        } catch (e: Exception) {
            _error.value = "Stream failed: ${e.message}"
            Log.e(TAG, "Streaming failed", e)
        } finally {
            _isGenerating.value = false
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _error.value = null
    }

    fun newConversation() {
        clearMessages()
        Log.d(TAG, "Started new conversation")
    }

    /**
     * Hand the recent conversation tail to [MemoryRepository] for extraction.
     * The repository owns all gating, debouncing, and dedup heuristics.
     */
    private suspend fun extractMemories() {
        val provider = llmProviderManager.getProvider() ?: return
        try {
            val recent = _messages.value.takeLast(4).map { it.role to it.content }
            memoryRepo.runExtraction(provider, recent)
        } catch (e: Exception) {
            Log.w(TAG, "Memory extraction failed (non-fatal)", e)
        }
    }

    companion object {
        private const val TAG = "ARIA.Chat"
        private const val MAX_TOOL_ROUNDS = 5

        /**
         * Only run the tool-router pre-pass when the full tool set exceeds this size.
         * Below it the extra round-trip costs more than it saves — small models handle
         * ~10 tools fine.
         */
        private const val ROUTER_TOOL_THRESHOLD = 10

        /** Tools whose results are purely internal LLM context — never shown to the user. */
        private val INTERNAL_TOOLS = setOf(
            "fetch_url",
            "activate_skill",
            "read_notifications",
            "lookup_contact",
            "get_calendar_events",
            "get_current_location",
            "list_apps",
            "get_weather",
            "remember",
            "forget",
        )
    }
}
