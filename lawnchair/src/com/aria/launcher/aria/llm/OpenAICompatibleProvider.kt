package com.aria.launcher.aria.llm

import android.util.Log
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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

class OpenAICompatibleProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String,
    private val apiKey: String,
    override val modelId: String,
    override val name: String = "OpenAI Compatible",
) : LlmProvider {

    private val completionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/v1/chat/completions"

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, maxTokens)
        val request = buildRequest(body)
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
            }
            parseResponse(responseBody)
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val body = buildRequestBody(systemPrompt, messages, maxTokens, stream = true)
        val request = buildRequest(body)

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    close()
                    return
                }
                try {
                    val parsed = json.parseToJsonElement(data).jsonObject
                    val choices = parsed["choices"]?.jsonArray ?: return
                    for (choice in choices) {
                        val delta = choice.jsonObject["delta"]?.jsonObject ?: continue
                        val content = delta["content"]?.jsonPrimitive?.contentOrNull
                        if (content != null) trySend(content)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE chunk", e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("SSE connection failed: ${response?.code}"))
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
        val request = buildRequest(body)
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
            }
            parseResponseWithTools(responseBody)
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        }
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        stream: Boolean = false,
        tools: List<ToolDefinition>? = null,
    ): String {
        val jsonBody = buildJsonObject {
            put("model", modelId)
            put("max_tokens", maxTokens)
            if (stream) put("stream", true)

            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                for (msg in messages) {
                    add(buildJsonObject {
                        put("role", if (msg.role == Role.ASSISTANT) "assistant" else "user")
                        put("content", msg.content)
                    })
                }
            }

            if (tools != null) {
                putJsonArray("tools") {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                putJsonObject("parameters") {
                                    put("type", "object")
                                    for ((key, value) in tool.inputSchema) {
                                        put(key, value)
                                    }
                                }
                            }
                        })
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonBody)
    }

    private fun buildRequest(body: String): Request =
        Request.Builder()
            .url(completionsUrl)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .build()

    private fun parseResponse(responseBody: String): LlmResult {
        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val choices = parsed["choices"]?.jsonArray
            if (choices.isNullOrEmpty()) return LlmResult.Error("No choices in response")
            val message = choices[0].jsonObject["message"]?.jsonObject
            val content = message?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
            LlmResult.Text(content)
        } catch (e: Exception) {
            LlmResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    private fun parseResponseWithTools(responseBody: String): LlmResult {
        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val choices = parsed["choices"]?.jsonArray
            if (choices.isNullOrEmpty()) return LlmResult.Error("No choices in response")
            val message = choices[0].jsonObject["message"]?.jsonObject
                ?: return LlmResult.Error("No message in response")
            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val toolCallsJson = message["tool_calls"]?.jsonArray

            if (toolCallsJson.isNullOrEmpty()) return LlmResult.Text(content)

            val toolCalls = toolCallsJson.map { tc ->
                val obj = tc.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val function = obj["function"]?.jsonObject ?: JsonObject(emptyMap())
                val name = function["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val args = try {
                    val argsStr = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                    json.parseToJsonElement(argsStr).jsonObject.toMap()
                } catch (e: Exception) {
                    emptyMap()
                }
                ToolCall(id, name, args)
            }
            LlmResult.ToolUse(content, toolCalls)
        } catch (e: Exception) {
            LlmResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "ARIA.OpenAI"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun gemini(client: OkHttpClient, json: Json, apiKey: String): OpenAICompatibleProvider =
            OpenAICompatibleProvider(
                client = client,
                json = json,
                baseUrl = "https://generativelanguage.googleapis.com",
                apiKey = apiKey,
                modelId = "gemini-2.0-flash",
                name = "Gemini",
            )

        fun openRouter(client: OkHttpClient, json: Json, apiKey: String, modelId: String = "anthropic/claude-sonnet-4"): OpenAICompatibleProvider =
            OpenAICompatibleProvider(
                client = client,
                json = json,
                baseUrl = "https://openrouter.ai/api",
                apiKey = apiKey,
                modelId = modelId,
                name = "OpenRouter",
            )
    }
}
