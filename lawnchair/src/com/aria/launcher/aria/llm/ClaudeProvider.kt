package com.aria.launcher.aria.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.coroutines.resume

class ClaudeProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val token: String,
    override val modelId: String = DEFAULT_MODEL,
    private val refreshToken: String? = null,
    private val onTokenRefreshed: ((newToken: String, newRefreshToken: String?) -> Unit)? = null,
) : LlmProvider {

    override val name: String = "Claude"

    private val isOAuthToken: Boolean get() = token.startsWith("sk-ant-oat01-")
    private var currentToken: String = token

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, maxTokens)
        val request = buildRequest(body)
        try {
            val response = client.newCall(request).await()
            val responseBody = response.body?.string() ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                if (response.code == 401 && isOAuthToken && refreshToken != null) {
                    val refreshed = refreshOAuthToken()
                    if (refreshed) return@withContext complete(systemPrompt, messages, maxTokens)
                }
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
                if (type == "content_block_delta") {
                    try {
                        val parsed = json.parseToJsonElement(data).jsonObject
                        val delta = parsed["delta"]?.jsonObject
                        val text = delta?.get("text")?.jsonPrimitive?.contentOrNull
                        if (text != null) trySend(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE delta", e)
                    }
                } else if (type == "message_stop") {
                    close()
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
            val response = client.newCall(request).await()
            val responseBody = response.body?.string() ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
            }
            parseResponse(responseBody)
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
            put("system", systemPrompt)
            if (stream) put("stream", true)

            putJsonArray("messages") {
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
                            put("name", tool.name)
                            put("description", tool.description)
                            putJsonObject("input_schema") {
                                put("type", "object")
                                for ((key, value) in tool.inputSchema) {
                                    put(key, value)
                                }
                            }
                        })
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonBody)
    }

    private fun buildRequest(body: String): Request {
        val builder = Request.Builder()
            .url(API_URL)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")

        if (isOAuthToken) {
            builder.header("Authorization", "Bearer $currentToken")
        } else {
            builder.header("x-api-key", currentToken)
        }

        return builder.build()
    }

    private fun parseResponse(responseBody: String): LlmResult {
        return try {
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val content = parsed["content"]?.jsonArray ?: return LlmResult.Error("No content in response")

            val textParts = mutableListOf<String>()
            val toolCalls = mutableListOf<ToolCall>()

            for (block in content) {
                val obj = block.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> {
                        obj["text"]?.jsonPrimitive?.contentOrNull?.let { textParts.add(it) }
                    }
                    "tool_use" -> {
                        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                        val input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                        toolCalls.add(ToolCall(id, name, input))
                    }
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

    private suspend fun refreshOAuthToken(): Boolean = withContext(Dispatchers.IO) {
        if (refreshToken == null) return@withContext false
        try {
            val body = buildJsonObject {
                put("grant_type", "refresh_token")
                put("refresh_token", refreshToken)
            }
            val request = Request.Builder()
                .url(TOKEN_REFRESH_URL)
                .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
                .header("content-type", "application/json")
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) return@withContext false
            val responseBody = response.body?.string() ?: return@withContext false
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val newToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull ?: return@withContext false
            val newRefresh = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull
            currentToken = newToken
            onTokenRefreshed?.invoke(newToken, newRefresh)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "ARIA.Claude"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val TOKEN_REFRESH_URL = "https://console.anthropic.com/api/oauth/token"
        private const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWith(Result.failure(e))
        }
    })
}
