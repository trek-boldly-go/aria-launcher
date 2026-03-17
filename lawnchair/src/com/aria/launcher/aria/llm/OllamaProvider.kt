package com.aria.launcher.aria.llm

import android.util.Log
import java.io.BufferedReader
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OllamaProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val serverUrl: String,
    override val modelId: String = DEFAULT_MODEL,
) : LlmProvider {

    override val name: String = "Ollama"

    private val baseUrl: String get() = serverUrl.trimEnd('/')

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, stream = false)
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", "application/json")
            .build()
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
            }
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val content = parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (content != null) LlmResult.Text(content) else LlmResult.Error("No content in response")
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val body = buildRequestBody(systemPrompt, messages, stream = true)
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("content-type", "application/json")
            .build()

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                try {
                    val reader = response.body?.charStream()?.let { BufferedReader(it) }
                    if (reader == null) {
                        close(IOException("Empty response body"))
                        return
                    }
                    reader.use { r ->
                        r.forEachLine { line ->
                            if (line.isBlank()) return@forEachLine
                            try {
                                val parsed = json.parseToJsonElement(line).jsonObject
                                val content = parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                                if (content != null) trySend(content)
                                val done = parsed["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                                if (done == true) close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse NDJSON line", e)
                            }
                        }
                    }
                    close()
                } catch (e: Exception) {
                    close(e)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }
        })

        awaitClose { call.cancel() }
    }

    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
    ): LlmResult {
        // Ollama supports tool calling via the /api/chat endpoint with tools parameter
        // For simplicity, fall back to non-tool completion with tool descriptions in system prompt
        val toolDescriptions = tools.joinToString("\n") { tool ->
            "- ${tool.name}: ${tool.description}"
        }
        val enhancedPrompt = "$systemPrompt\n\nAvailable tools:\n$toolDescriptions\n\n" +
            "To use a tool, respond with a JSON object: {\"tool\": \"tool_name\", \"arguments\": {...}}"
        return complete(enhancedPrompt, messages, maxTokens)
    }

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<ChatMessage>,
        stream: Boolean,
    ): String {
        val jsonBody = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                for (msg in messages) {
                    add(
                        buildJsonObject {
                            put("role", if (msg.role == Role.ASSISTANT) "assistant" else "user")
                            put("content", msg.content)
                        },
                    )
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonBody)
    }

    companion object {
        private const val TAG = "ARIA.Ollama"
        private const val DEFAULT_MODEL = "qwen2.5:7b"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
