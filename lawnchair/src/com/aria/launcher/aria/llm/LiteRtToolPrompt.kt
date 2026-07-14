// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Pure helpers for LiteRT on-device prompted tool-calling and history rendering.
 *
 * On-device models (Gemma-3n class) have no native tool-calling and no server-side
 * conversation state. We describe the available tools in the system prompt and ask
 * the model to reply with a single JSON object when it wants to call one. These
 * functions are pure so they can be unit-tested without the LiteRT engine.
 */
object LiteRtToolPrompt {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Outcome of parsing a model reply under the prompted tool-calling protocol. */
    sealed interface Reply {
        data class Invocation(val name: String, val arguments: Map<String, JsonElement>) : Reply

        data class PlainText(val text: String) : Reply
    }

    /**
     * Renders a compact tool catalog plus the reply protocol, to append to the system
     * prompt. Returns an empty string when there are no tools.
     */
    fun renderToolInstructions(tools: List<ToolDefinition>): String {
        if (tools.isEmpty()) return ""
        return buildString {
            append("You can call one of these tools when it helps answer the user:\n")
            for (tool in tools) {
                append("- ").append(tool.name).append(": ").append(collapse(tool.description))
                val params = paramSummary(tool)
                if (params.isNotEmpty()) append(" — params: ").append(params)
                append('\n')
            }
            append(
                "\nTo call a tool, reply with ONLY this JSON and nothing else:\n" +
                    "{\"tool\": \"<name>\", \"arguments\": { ... }}\n" +
                    "Otherwise reply with a normal text answer. Never mix prose and the JSON.",
            )
        }
    }

    /**
     * Parses a model reply. If it is (or contains) a `{"tool": …, "arguments": …}`
     * object, returns [Reply.Invocation]; otherwise [Reply.PlainText] carrying the
     * original, unmodified text.
     */
    fun parseReply(reply: String): Reply {
        // Try each balanced { … } span in order. Small on-device models routinely wrap the
        // JSON in narration that itself contains braces, so a greedy first-{ /last-} span
        // could straddle a stray brace and fail to parse a call that is actually present.
        for (candidate in jsonObjectCandidates(reply)) {
            val obj = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull() ?: continue
            // Only "tool" is a tool call — the rendered protocol and contentForMessage both
            // emit "tool". Do not accept "name"; ordinary JSON like {"name":"Alice"} is not
            // an invocation and must round-trip as plain text.
            val name = obj["tool"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val arguments = (obj["arguments"] as? JsonObject) ?: emptyMap()
            return Reply.Invocation(name, arguments)
        }
        return Reply.PlainText(reply)
    }

    /**
     * The text to replay for a history message.
     * - TOOL results are folded into plain user-turn prose (see [renderToolResult]).
     * - Assistant tool requests with blank content are rendered as the JSON invocation
     *   they emitted, so the on-device model (which keeps no native tool state) sees its
     *   own prior action.
     */
    fun contentForMessage(message: ChatMessage): String {
        if (message.role == Role.TOOL) return renderToolResult(message)
        // Render every tool call, not just the first: parallel calls (Claude/Gemini) each
        // get a matching TOOL result, so dropping the rest would leave the replayed history
        // showing results for invocations the model never appears to have made.
        val invocation = message.toolCalls
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n", transform = ::renderInvocation)
        return when {
            message.content.isBlank() -> invocation ?: message.content

            invocation == null -> message.content

            // Both narration and a tool call: render both so neither is silently dropped.
            else -> message.content + "\n" + invocation
        }
    }

    private fun renderInvocation(call: ToolCall): String {
        val obj = buildJsonObject {
            put("tool", call.name)
            put("arguments", JsonObject(call.arguments))
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Renders a TOOL result as plain user-turn text. On-device models handle a dedicated
     * TOOL role inconsistently, so tool output is folded into a normal user message the
     * model reliably reads.
     */
    fun renderToolResult(message: ChatMessage): String {
        val label = message.toolName?.takeIf { it.isNotBlank() }
        return if (label != null) "Tool result ($label): ${message.content}" else "Tool result: ${message.content}"
    }

    private fun paramSummary(tool: ToolDefinition): String {
        val properties = tool.inputSchema["properties"] as? JsonObject ?: return ""
        val required = (tool.inputSchema["required"] as? JsonArray)
            ?.mapNotNull { it.stringOrNull() }
            ?.toSet()
            ?: emptySet()
        return properties.keys.joinToString(", ") { key -> if (key in required) "$key*" else key }
    }

    /**
     * Yields each top-level balanced `{ … }` span in [text], left to right. Brace
     * matching ignores braces inside string literals (and their escapes), so a `}`
     * in a value never closes the object early. Surrounding prose and code fences
     * fall between spans and are skipped.
     */
    private fun jsonObjectCandidates(text: String): Sequence<String> = sequence {
        var i = 0
        while (i < text.length) {
            if (text[i] == '{') {
                val end = matchingBrace(text, i)
                if (end > i) {
                    yield(text.substring(i, end + 1))
                    i = end + 1
                    continue
                }
            }
            i++
        }
    }

    /** Index of the `}` matching the `{` at [open], or -1 if unbalanced. String-aware. */
    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private val WHITESPACE = Regex("\\s+")
}
