// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.chat

import android.content.Context
import android.util.Log
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.UserMemory
import com.aria.launcher.aria.data.UserMemoryDao
import com.aria.launcher.aria.engine.AppActivityCatalog
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import com.aria.launcher.aria.llm.AriaPrompts
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val userMemoryDao: UserMemoryDao,
    private val ariaPreferences: AriaPreferences,
    private val ariaChatHandler: AriaChatHandler,
    private val capabilityCatalog: DeviceCapabilityCatalog,
    private val appActivityCatalog: AppActivityCatalog,
    private val httpClient: OkHttpClient,
    private val agentSkillManager: AgentSkillManager,
    private val appLabelResolver: AppLabelResolver,
) {
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Pending confirmation action waiting for user to say "Yes" or "No". */
    private var pendingConfirmation: ConfirmationAction? = null

    private val toolExecutor = ToolExecutor(context, httpClient, agentSkillManager, appLabelResolver)

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
                if (lowerText.startsWith("yes") || lowerText == "y" || lowerText.contains("save it")) {
                    pendingConfirmation = null
                    val response = when (pendingAction) {
                        is ConfirmationAction.SaveRule -> ariaChatHandler.confirmSaveRule(pendingAction.rule)
                    }
                    _messages.value += UiMessage(Role.ASSISTANT, response.text)
                    return
                } else if (lowerText.startsWith("no") || lowerText == "n") {
                    pendingConfirmation = null
                    _messages.value += UiMessage(Role.ASSISTANT, "Got it, rule discarded.")
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

            // Load user memories for context
            val memories = withContext(Dispatchers.IO) {
                userMemoryDao.getRecent(20)
            }

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
            val tools = AriaPrompts.buildTools(capabilities, skillNames, notifContentEnabled)

            val chatMessages = _messages.value.map { msg ->
                ChatMessage(role = msg.role, content = msg.content)
            }

            var round = 0
            var currentMessages = chatMessages

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

                        // Feed results back to the AI for continuation
                        val toolResultText = toolResults.joinToString("\n") {
                            "[Tool ${it.toolName}]: ${it.result}"
                        }
                        currentMessages = currentMessages + listOf(
                            ChatMessage(Role.ASSISTANT, result.content),
                            ChatMessage(Role.USER, toolResultText),
                        )
                        round++
                    }

                    is LlmResult.Error -> {
                        _error.value = result.message
                        Log.e(TAG, "LLM error: ${result.message}", result.cause)
                        break
                    }
                }
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

            val memories = withContext(Dispatchers.IO) {
                userMemoryDao.getRecent(20)
            }

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
     * Extract memorable facts from the recent conversation and store them.
     * Runs after each assistant response. Uses the LLM to identify facts worth remembering.
     */
    private suspend fun extractMemories() {
        val memoryEnabled = ariaPreferences.memoryEnabled.first()
        if (!memoryEnabled) return

        val recentMessages = _messages.value.takeLast(4) // Last 2 exchanges
        if (recentMessages.size < 2) return

        val provider = llmProviderManager.getProvider() ?: return

        try {
            val existingMemories = withContext(Dispatchers.IO) {
                userMemoryDao.getRecent(30)
            }
            val existingFacts = existingMemories.map { it.fact }

            val extractionPrompt = AriaPrompts.buildMemoryExtractionPrompt(
                recentMessages = recentMessages.map { "${it.role.name}: ${it.content}" },
                existingMemories = existingFacts,
            )

            val result = withContext(Dispatchers.IO) {
                provider.complete(
                    systemPrompt = extractionPrompt,
                    messages = listOf(ChatMessage(Role.USER, "Extract noteworthy memories from the conversation above.")),
                )
            }

            if (result is LlmResult.Text) {
                parseAndStoreMemories(result.content)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Memory extraction failed (non-fatal)", e)
        }
    }

    private suspend fun parseAndStoreMemories(response: String) {
        // Expected format: one fact per line, prefixed with category in brackets
        // e.g. "[preference] User prefers dark mode"
        // or just plain facts if no category
        val lines = response.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("NONE", ignoreCase = true) }

        for (line in lines) {
            val categoryMatch = Regex("""\[(\w+)](.+)""").find(line)
            val (category, fact) = if (categoryMatch != null) {
                categoryMatch.groupValues[1].lowercase() to categoryMatch.groupValues[2].trim()
            } else {
                "general" to line
            }

            if (fact.length < 5 || fact.length > 300) continue

            // Check for near-duplicates
            val existing = withContext(Dispatchers.IO) {
                userMemoryDao.search(fact.take(30))
            }
            if (existing.any { it.fact.equals(fact, ignoreCase = true) }) continue

            withContext(Dispatchers.IO) {
                userMemoryDao.insert(
                    UserMemory(
                        fact = fact,
                        category = category,
                    ),
                )
            }
            Log.d(TAG, "Stored memory [$category]: $fact")
        }
    }

    companion object {
        private const val TAG = "ARIA.Chat"
        private const val MAX_TOOL_ROUNDS = 5

        /** Tools whose results are purely internal LLM context — never shown to the user. */
        private val INTERNAL_TOOLS = setOf("fetch_url", "activate_skill", "read_notifications")
    }
}
