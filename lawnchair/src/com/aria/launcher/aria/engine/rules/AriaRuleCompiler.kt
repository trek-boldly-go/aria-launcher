// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine.rules

import android.util.Log
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compiles a natural language rule string into a structured [AriaRule].
 * This is the ONLY place the LLM is involved in the rule system — runs once when
 * the user creates or edits a rule. Runtime evaluation is zero-LLM (see [AriaRuleEvaluator]).
 */
@Singleton
class AriaRuleCompiler @Inject constructor(
    private val llmProviderManager: LlmProviderManager,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun compile(userInput: String): RuleCompilationResult {
        val provider = llmProviderManager.getProvider()
            ?: return RuleCompilationResult.ParseError("No LLM provider configured.")

        val result = provider.complete(
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(ChatMessage(Role.USER, userInput)),
            maxTokens = 512,
        )

        val responseText = when (result) {
            is LlmResult.Text -> result.content

            is LlmResult.Error -> return RuleCompilationResult.ParseError(
                "LLM error: ${result.message}",
            )

            else -> return RuleCompilationResult.ParseError("Unexpected LLM response type.")
        }

        return parseCompilationResult(responseText, userInput)
    }

    private fun parseCompilationResult(
        responseJson: String,
        originalInput: String,
    ): RuleCompilationResult {
        return try {
            // Strip markdown fences if present
            val cleaned = responseJson
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val parsed = json.parseToJsonElement(cleaned).jsonObject
            val confidence = parsed["confidence"]?.jsonPrimitive?.float ?: 0.5f
            val clarification = parsed["clarificationNeeded"]?.jsonPrimitive?.contentOrNull
            val summary = parsed["humanReadableSummary"]?.jsonPrimitive?.contentOrNull ?: ""
            val needsConfirmation = parsed["needsUserConfirmation"]?.jsonPrimitive?.boolean ?: false

            if (!clarification.isNullOrBlank()) {
                return RuleCompilationResult.NeedsClarification(clarification)
            }

            if (confidence < 0.7f) {
                return RuleCompilationResult.LowConfidence(summary, confidence)
            }

            val triggerObj = parsed["trigger"]?.jsonObject
                ?: return RuleCompilationResult.ParseError("Missing trigger in LLM response.")
            val actionObj = parsed["action"]?.jsonObject
                ?: return RuleCompilationResult.ParseError("Missing action in LLM response.")

            val trigger = json.decodeFromJsonElement(RuleTrigger.serializer(), triggerObj)
            val action = json.decodeFromJsonElement(RuleAction.serializer(), actionObj)

            RuleCompilationResult.Success(
                rule = AriaRule(
                    naturalLanguageSource = originalInput,
                    humanReadableSummary = summary,
                    trigger = trigger,
                    action = action,
                    confidence = confidence,
                    needsUserConfirmation = needsConfirmation,
                ),
                humanReadableSummary = summary,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse LLM rule response", e)
            RuleCompilationResult.ParseError("Could not understand that rule. Try rephrasing.")
        }
    }

    companion object {
        private const val TAG = "ARIA.RuleCompiler"

        private val SYSTEM_PROMPT = """
            You are a rule compiler for ARIA, an Android launcher assistant.
            The user will describe a rule in natural language.
            Your job is to convert it into a structured JSON rule object.

            Available trigger types:
            - WifiSsidTrigger: { "type": "wifi_ssid", "pattern": "string",
              "matchType": "EXACT|CONTAINS|STARTS_WITH|REGEX" }
            - VenueCategoryTrigger: { "type": "venue_category",
              "category": "FAST_FOOD|COFFEE|RETAIL|HEALTHCARE|HOTEL|TRAVEL|OFFICE|EDUCATION|ENTERTAINMENT" }
            - TimeTrigger: { "type": "time", "startHour": 0-23, "endHour": 0-23,
              "daysOfWeek": [1-7] or null for every day }
            - AppOpenedTrigger: { "type": "app_opened", "packageName": "com.example.app" }
            - CalendarEventTrigger: { "type": "calendar_event",
              "titleKeywords": ["keyword1"], "minutesBefore": 15 }
            - AndroidAutoTrigger: { "type": "android_auto", "connectedCarName": "string or null" }
            - CompoundTrigger: { "type": "compound", "operator": "AND|OR",
              "triggers": [...] }

            Available action types:
            - SurfaceApp: { "type": "surface_app", "packageName": "com.example.app",
              "priority": "ALWAYS_SHOW|BOOST|PIN_TO_DOCK" }
            - SuppressApp: { "type": "suppress_app", "packageName": "com.example.app" }
            - ShowCard: { "type": "show_card", "cardType": "string",
              "headline": "string", "subtext": "string or null", "intentUri": "string or null" }
            - OpenApp: { "type": "open_app", "packageName": "com.example.app",
              "intentUri": "string or null" }
            - SendMessage: { "type": "send_message", "contactName": "string",
              "messageTemplate": "string" }
            - SetSpace: { "type": "set_space", "spaceName": "string" }

            Respond ONLY with valid JSON in this format:
            {
              "trigger": { ... },
              "action": { ... },
              "confidence": 0.0-1.0,
              "needsUserConfirmation": true/false,
              "humanReadableSummary": "When [trigger], ARIA will [action]",
              "clarificationNeeded": "string or null"
            }

            Set needsUserConfirmation to true for any action that sends messages,
            makes purchases, or opens apps automatically without user initiation.
            Set confidence below 0.8 if the rule is ambiguous.
            Set clarificationNeeded if you need more information to compile the rule.
        """.trimIndent()
    }
}

sealed class RuleCompilationResult {
    data class Success(val rule: AriaRule, val humanReadableSummary: String) : RuleCompilationResult()

    data class NeedsClarification(val question: String) : RuleCompilationResult()

    data class LowConfidence(val summary: String, val confidence: Float) : RuleCompilationResult()

    data class ParseError(val message: String) : RuleCompilationResult()
}
