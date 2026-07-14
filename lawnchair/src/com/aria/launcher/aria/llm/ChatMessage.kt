package com.aria.launcher.aria.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class Role { USER, ASSISTANT, SYSTEM, TOOL }

/**
 * Requested shape of a completion's output. Providers that support constrained
 * decoding (Ollama, OpenAI-compatible) enforce it on the wire; providers without
 * a native equivalent (Claude, Gemini, LiteRT) ignore it and rely on the prompt
 * already instructing the model to emit JSON — so [None] behavior is unchanged.
 */
sealed class ResponseFormat {
    /** No constraint — free-form text. */
    object None : ResponseFormat()

    /** Any syntactically valid JSON object. */
    object Json : ResponseFormat()

    /** JSON conforming to the given JSON Schema. */
    data class Schema(val schema: JsonObject) : ResponseFormat()
}

data class ChatMessage(
    val role: Role,
    val content: String,
    /** Set on ASSISTANT messages that requested tools; echoed back so the model sees its own calls. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Set on TOOL messages: the id of the assistant tool call this result answers. */
    val toolCallId: String? = null,
    /** Set on TOOL messages: the name of the tool that produced this result (some APIs require it). */
    val toolName: String? = null,
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
