package com.aria.launcher.aria.llm

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Native Gemini API provider. Uses the generateContent endpoint directly
 * (not the OpenAI-compatible wrapper, which has zero free-tier quota).
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val apiKey: String,
    override val modelId: String = DEFAULT_MODEL,
) : LlmProvider {

    override val name: String = "Gemini"

    private fun generateUrl(stream: Boolean = false): String {
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$BASE_URL/models/$modelId:$action"
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, maxTokens)
        val request = buildRequest(body, stream = false)
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "API error ${response.code}: $responseBody")
                    return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
                }
                parseResponse(responseBody)
            }
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val body = buildRequestBody(systemPrompt, messages, maxTokens)
        val request = buildRequest(body, stream = true)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val parsed = json.parseToJsonElement(data).jsonObject
                    val candidates = parsed["candidates"]?.jsonArray ?: return
                    for (candidate in candidates) {
                        val content = candidate.jsonObject["content"]?.jsonObject ?: continue
                        val parts = content["parts"]?.jsonArray ?: continue
                        for (part in parts) {
                            val text = part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                            if (text != null) trySend(text)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE chunk", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("SSE connection failed: ${response?.code}"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose { eventSource.cancel() }
    }

    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, maxTokens, tools = tools)
        val request = buildRequest(body, stream = false)
        try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                if (!response.isSuccessful) {
                    return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
                }
                parseResponseWithTools(responseBody)
            }
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        }
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        tools: List<ToolDefinition>? = null,
    ): String {
        val jsonBody = buildJsonObject {
            // System instruction
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", systemPrompt) })
                }
            }

            // Conversation contents
            putJsonArray("contents") {
                for (msg in messages) {
                    add(
                        buildJsonObject {
                            put("role", if (msg.role == Role.ASSISTANT) "model" else "user")
                            putJsonArray("parts") {
                                add(buildJsonObject { put("text", msg.content) })
                            }
                        },
                    )
                }
            }

            // Generation config
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxTokens)
            }

            // Tools (function calling)
            if (tools != null && tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            putJsonArray("functionDeclarations") {
                                for (tool in tools) {
                                    add(
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            putJsonObject("parameters") {
                                                put("type", "OBJECT")
                                                for ((key, value) in tool.inputSchema) {
                                                    put(key, value)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonBody)
    }

    private fun buildRequest(body: String, stream: Boolean): Request = Request.Builder()
        .url(generateUrl(stream))
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .header("Content-Type", "application/json")
        .header("x-goog-api-key", apiKey)
        .build()

    private fun parseResponse(responseBody: String): LlmResult {
        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val candidates = parsed["candidates"]?.jsonArray
            if (candidates.isNullOrEmpty()) return LlmResult.Error("No candidates in response")
            val content = candidates[0].jsonObject["content"]?.jsonObject
                ?: return LlmResult.Error("No content in candidate")
            val parts = content["parts"]?.jsonArray
                ?: return LlmResult.Error("No parts in content")

            val textParts = parts.mapNotNull { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
            }
            LlmResult.Text(textParts.joinToString(""))
        } catch (e: Exception) {
            LlmResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    private fun parseResponseWithTools(responseBody: String): LlmResult {
        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val candidates = parsed["candidates"]?.jsonArray
            if (candidates.isNullOrEmpty()) return LlmResult.Error("No candidates in response")
            val content = candidates[0].jsonObject["content"]?.jsonObject
                ?: return LlmResult.Error("No content in candidate")
            val parts = content["parts"]?.jsonArray
                ?: return LlmResult.Error("No parts in content")

            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<ToolCall>()

            for (part in parts) {
                val obj = part.jsonObject
                // Text part
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { textParts.add(it) }
                // Function call part
                obj["functionCall"]?.jsonObject?.let { fc ->
                    val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val args = fc["args"]?.jsonObject?.toMap() ?: emptyMap()
                    toolCalls.add(ToolCall(id = name, name = name, arguments = args))
                }
            }

            val text = textParts.joinToString("")
            if (toolCalls.isNotEmpty()) {
                LlmResult.ToolUse(text, toolCalls)
            } else {
                LlmResult.Text(text)
            }
        } catch (e: Exception) {
            LlmResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "ARIA.Gemini"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Models available for selection in the UI. */
        val AVAILABLE_MODELS = listOf(
            "gemini-2.5-flash" to "Gemini 2.5 Flash (Recommended)",
            "gemini-2.0-flash" to "Gemini 2.0 Flash",
            "gemini-2.5-pro" to "Gemini 2.5 Pro (Lower free limits)",
        )
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
