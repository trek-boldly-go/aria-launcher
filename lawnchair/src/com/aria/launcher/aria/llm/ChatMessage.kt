package com.aria.launcher.aria.llm

import kotlinx.serialization.Serializable

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val role: Role,
    val content: String,
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, kotlinx.serialization.json.JsonElement>,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement>,
)

sealed class LlmResult {
    data class Text(val content: String) : LlmResult()
    data class ToolUse(val content: String, val toolCalls: List<ToolCall>) : LlmResult()
    data class Error(val message: String, val cause: Throwable? = null) : LlmResult()
}
