// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.skills

import android.content.Context
import android.util.Log
import com.aria.launcher.aria.data.AppSkill
import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.SkillExecutor
import com.aria.launcher.aria.llm.AriaPrompts
import com.aria.launcher.aria.llm.ChatMessage
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.Role
import com.aria.launcher.aria.llm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Executes agentskills on a scheduled (cron) basis via the LLM.
 * Skills with `aria-trigger: scheduled` are run by [SkillOrchestrator] at their
 * configured refresh interval. This executor loads the SKILL.md instructions,
 * calls the LLM with `fetch_url` available, and parses the response into a
 * [SkillResult] for caching in the database.
 */
class ScheduledSkillExecutor(
    private val appContext: Context,
    private val agentSkillManager: AgentSkillManager,
    private val llmProviderManager: LlmProviderManager,
    private val httpClient: OkHttpClient,
    private val json: Json,
) : SkillExecutor {

    override val supportedSkillIds: Set<String>
        get() {
            // Dynamically return IDs of installed scheduled skills
            // This is called from the IO dispatcher by SkillOrchestrator
            return try {
                kotlinx.coroutines.runBlocking {
                    agentSkillManager.getScheduledSkills().map { it.name }.toSet()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get scheduled skill IDs", e)
                emptySet()
            }
        }

    override suspend fun execute(skill: AppSkill): SkillResult? = withContext(Dispatchers.IO) {
        val provider = llmProviderManager.getProvider() ?: run {
            Log.d(TAG, "No LLM provider configured, skipping ${skill.id}")
            return@withContext null
        }

        val content = agentSkillManager.getSkillContent(skill.id) ?: run {
            Log.w(TAG, "Skill content not found: ${skill.id}")
            return@withContext null
        }

        val config = agentSkillManager.getConfig(skill.id)
        val configText = if (config.isNotEmpty()) {
            "User configuration:\n" + config.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        } else {
            ""
        }

        val systemPrompt = """
            You are ARIA, an AI assistant. Execute the following skill and produce a result
            suitable for a home screen card.

            ## Skill Instructions
            $content

            $configText

            ## Output Format
            After gathering data, respond with a brief result in this exact JSON format:
            {
              "title": "<short headline, max 50 chars>",
              "body": "<summary, max 200 chars>",
              "action_label": "<button text, max 20 chars>",
              "action_type": "OPEN_APP",
              "action_payload": "<package name or deep link>",
              "priority": <0.0-1.0 float>
            }

            Respond ONLY with the JSON. No markdown fences, no explanation.
        """.trimIndent()

        val tools = AriaPrompts.coreTools.filter { it.name == "fetch_url" }
        val toolExecutor = com.aria.launcher.aria.chat.ToolExecutor(appContext, httpClient)

        var messages = listOf(ChatMessage(Role.USER, "Execute this skill now."))
        var round = 0

        while (round < MAX_TOOL_ROUNDS) {
            val result = provider.completeWithTools(
                systemPrompt = systemPrompt,
                messages = messages,
                tools = tools,
                maxTokens = 512,
            )

            when (result) {
                is LlmResult.Text -> {
                    return@withContext parseSkillResult(skill.id, result.content)
                }

                is LlmResult.ToolUse -> {
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
                    Log.w(TAG, "Scheduled skill ${skill.id} LLM error: ${result.message}")
                    return@withContext null
                }
            }
        }

        Log.w(TAG, "Scheduled skill ${skill.id} exceeded max tool rounds")
        null
    }

    private fun parseSkillResult(skillId: String, response: String): SkillResult? {
        return try {
            val cleaned = response.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val obj = json.parseToJsonElement(cleaned)
                .let { it as? kotlinx.serialization.json.JsonObject } ?: return null

            val title = obj["title"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: return null
            val body = obj["body"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: ""
            val actionLabel = obj["action_label"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: "Open"
            val actionType = obj["action_type"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: "OPEN_APP"
            val actionPayload = obj["action_payload"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: ""
            val priority = obj["priority"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull()
            } ?: 0.5f

            val actions = listOf(SkillAction(actionLabel, actionType, actionPayload))

            SkillResult(
                skillId = skillId,
                title = title,
                body = body,
                actions = json.encodeToString(actions),
                priority = priority,
                timestamp = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 60 * 60 * 1000L,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse skill result for $skillId", e)
            null
        }
    }

    companion object {
        private const val TAG = "ARIA.ScheduledSkill"
        private const val MAX_TOOL_ROUNDS = 3
    }
}
