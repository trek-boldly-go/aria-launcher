// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.chat

import android.util.Log
import com.aria.launcher.aria.engine.rules.AriaRule
import com.aria.launcher.aria.engine.rules.AriaRuleCompiler
import com.aria.launcher.aria.engine.rules.AriaRuleDao
import com.aria.launcher.aria.engine.rules.RuleCompilationResult
import com.aria.launcher.aria.llm.LlmProviderManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Possible confirmation actions returned with a [ChatResponse]. */
sealed class ConfirmationAction {
    /** User should confirm before ARIA saves this rule to the database. */
    data class SaveRule(val rule: AriaRule) : ConfirmationAction()
}

/**
 * Chat response returned by [AriaChatHandler].
 *
 * @param text Main assistant message text.
 * @param confirmationAction Pending action that requires user confirmation, if any.
 * @param suggestedReplies Quick-reply chips to surface below the message.
 */
data class ChatResponse(
    val text: String,
    val confirmationAction: ConfirmationAction? = null,
    val suggestedReplies: List<String> = emptyList(),
)

/**
 * Pre-processes incoming chat input before it reaches the general LLM loop.
 *
 * Detects rule creation intent via keyword patterns and routes those messages to
 * [AriaRuleCompiler] instead. All other input falls through to [ChatState]'s normal
 * LLM tool loop via [isRuleCreationIntent] returning false.
 *
 * The LLM is only involved in the rule system at creation time. Runtime evaluation
 * is zero-LLM (see AriaRuleEvaluator).
 */
@Singleton
class AriaChatHandler @Inject constructor(
    private val llmProviderManager: LlmProviderManager,
    private val ruleCompiler: AriaRuleCompiler,
    private val ruleDao: AriaRuleDao,
) {
    // Keywords that signal rule creation intent
    private val ruleIntentPatterns = listOf(
        Regex("always (show|open|surface|display)", RegexOption.IGNORE_CASE),
        Regex("when(ever)? (i|you|my|the)", RegexOption.IGNORE_CASE),
        Regex("every time", RegexOption.IGNORE_CASE),
        Regex("from now on", RegexOption.IGNORE_CASE),
        Regex("if (you see|i'm at|i am at|connected to)", RegexOption.IGNORE_CASE),
        Regex("it means", RegexOption.IGNORE_CASE),
        Regex("go ahead and", RegexOption.IGNORE_CASE),
        Regex("automatically", RegexOption.IGNORE_CASE),
        Regex("remember (that|to|when)", RegexOption.IGNORE_CASE),
    )

    private val showRulesPatterns = listOf(
        Regex("show (my )?rules", RegexOption.IGNORE_CASE),
        Regex("list (my )?rules", RegexOption.IGNORE_CASE),
        Regex("what rules", RegexOption.IGNORE_CASE),
        Regex("my rules", RegexOption.IGNORE_CASE),
    )

    fun isRuleCreationIntent(input: String): Boolean = ruleIntentPatterns.any { it.containsMatchIn(input) }

    fun isShowRulesIntent(input: String): Boolean = showRulesPatterns.any { it.containsMatchIn(input) }

    /**
     * Handle a rule creation request. Returns a [ChatResponse] with a confirmation
     * action if compilation succeeds, or an error/clarification message otherwise.
     */
    suspend fun handleRuleCreation(userInput: String): ChatResponse {
        return when (val result = withContext(Dispatchers.IO) { ruleCompiler.compile(userInput) }) {
            is RuleCompilationResult.Success -> {
                Log.d(TAG, "Rule compiled: ${result.humanReadableSummary}")
                ChatResponse(
                    text = "Got it. Here's what I'll do:\n\n**${result.humanReadableSummary}**\n\nShould I save this rule?",
                    confirmationAction = ConfirmationAction.SaveRule(result.rule),
                    suggestedReplies = listOf("Yes, save it", "No", "Change it"),
                )
            }

            is RuleCompilationResult.NeedsClarification ->
                ChatResponse(
                    text = result.question,
                    suggestedReplies = emptyList(),
                )

            is RuleCompilationResult.LowConfidence ->
                ChatResponse(
                    text = "I think you mean: ${result.summary}\n\nIs that right? (Confidence: ${(result.confidence * 100).toInt()}%)",
                    suggestedReplies = listOf("Yes, that's right", "Not quite"),
                )

            is RuleCompilationResult.ParseError ->
                ChatResponse(text = result.message)
        }
    }

    /**
     * Handle a "show my rules" request. Returns a summary of saved rules.
     */
    suspend fun handleShowRules(): ChatResponse {
        val rules = withContext(Dispatchers.IO) { ruleDao.getAllRules() }
        return if (rules.isEmpty()) {
            ChatResponse(
                text = "You don't have any rules saved yet. Try saying something like:\n\n" +
                    "_\"Always show me Spotify when I connect to my car\"_\n\n" +
                    "and I'll save it as a rule.",
            )
        } else {
            val summary = rules.take(5).joinToString("\n") { rule ->
                val status = if (rule.isEnabled) "✓" else "✗"
                "[$status] ${rule.humanReadableSummary.ifBlank { rule.naturalLanguageSource }}"
            }
            val footer = if (rules.size > 5) "\n…and ${rules.size - 5} more." else ""
            ChatResponse(
                text = "You have ${rules.size} rule${if (rules.size == 1) "" else "s"}:\n\n$summary$footer\n\nManage them in **Settings → ARIA Rules**.",
            )
        }
    }

    /**
     * Confirm and save a pending rule to the database.
     */
    suspend fun confirmSaveRule(rule: AriaRule): ChatResponse {
        val id = withContext(Dispatchers.IO) { ruleDao.insert(rule) }
        Log.d(TAG, "Rule saved (id=$id): ${rule.humanReadableSummary}")
        return ChatResponse(text = "Rule saved. I'll start applying it right away.")
    }

    companion object {
        private const val TAG = "ARIA.ChatHandler"
    }
}
