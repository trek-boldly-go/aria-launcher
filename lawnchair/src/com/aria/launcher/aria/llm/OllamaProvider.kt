package com.aria.launcher.aria.llm

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

class OllamaProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val serverUrl: String,
    override val modelId: String = DEFAULT_MODEL,
    private val authConfig: AuthConfig = AuthConfig.None,
) : LlmProvider {

    override val name: String = "Ollama"

    private val baseUrl: String get() = serverUrl.trimEnd('/')

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        responseFormat: ResponseFormat,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, stream = false, responseFormat = responseFormat)
        val request = buildApiRequest("$baseUrl/api/chat", body)
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
            }
            if (responseBody.trimStart().startsWith("<")) {
                return@withContext LlmResult.Error(
                    "Server returned HTML instead of JSON. If using Cloudflare Access, check your Service Token headers.",
                )
            }
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val content = parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            if (content != null) LlmResult.Text(content) else LlmResult.Error("No content in response")
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            LlmResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val body = buildRequestBody(systemPrompt, messages, stream = true)
        val request = buildApiRequest("$baseUrl/api/chat", body)

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        response.close()
                        close(IOException("HTTP ${response.code}"))
                        return
                    }
                    val reader = response.body?.charStream()?.let { BufferedReader(it) }
                    if (reader == null) {
                        close(IOException("Empty response body"))
                        return
                    }
                    reader.use { r ->
                        val firstLine = r.readLine()
                        if (firstLine == null) {
                            close()
                            return
                        }
                        if (firstLine.trimStart().startsWith("<")) {
                            close(
                                IOException(
                                    "Server returned HTML instead of JSON. " +
                                        "If using Cloudflare Access, check your Service Token headers.",
                                ),
                            )
                            return
                        }
                        val allLines = sequenceOf(firstLine) + r.lineSequence()
                        for (line in allLines) {
                            if (line.isBlank()) continue
                            try {
                                val parsed = json.parseToJsonElement(line).jsonObject
                                val content = parsed["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                                if (content != null) trySend(content)
                                val done = parsed["done"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                                if (done == true) {
                                    close()
                                    return
                                }
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
        responseFormat: ResponseFormat,
    ): LlmResult = withContext(Dispatchers.IO) {
        val body = buildRequestBody(systemPrompt, messages, stream = false, tools = tools, responseFormat = responseFormat)
        val request = buildApiRequest("$baseUrl/api/chat", body)
        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return@withContext LlmResult.Error("Empty response")
            if (!response.isSuccessful) {
                return@withContext LlmResult.Error("HTTP ${response.code}: $responseBody")
            }
            if (responseBody.trimStart().startsWith("<")) {
                return@withContext LlmResult.Error(
                    "Server returned HTML instead of JSON. If using Cloudflare Access, check your Service Token headers.",
                )
            }
            val parsed = json.parseToJsonElement(responseBody).jsonObject
            val message = parsed["message"]?.jsonObject
                ?: return@withContext LlmResult.Error("No message in response")

            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val toolCallsJson = message["tool_calls"]?.jsonArray

            if (toolCallsJson != null && toolCallsJson.isNotEmpty()) {
                val toolCalls = toolCallsJson.mapNotNull { element ->
                    val fn = element.jsonObject["function"]?.jsonObject ?: return@mapNotNull null
                    val fnName = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val args = fn["arguments"]?.jsonObject
                        ?.mapValues { (_, v) -> v }
                        ?: emptyMap()
                    ToolCall(
                        id = UUID.randomUUID().toString(),
                        name = fnName,
                        arguments = args,
                    )
                }
                if (toolCalls.isNotEmpty()) {
                    LlmResult.ToolUse(content, toolCalls)
                } else {
                    if (content.isNotBlank()) LlmResult.Text(content) else LlmResult.Error("No content in response")
                }
            } else {
                if (content.isNotBlank()) LlmResult.Text(content) else LlmResult.Error("No content in response")
            }
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            LlmResult.Error("Failed to parse response: ${e.message}", e)
        }
    }

    private fun buildApiRequest(url: String, body: String): Request = Request.Builder()
        .url(url)
        .post(body.toRequestBody(JSON_MEDIA_TYPE))
        .header("Content-Type", "application/json")
        .applyAuth(authConfig)
        .build()

    private fun buildRequestBody(
        systemPrompt: String,
        messages: List<ChatMessage>,
        stream: Boolean,
        tools: List<ToolDefinition>? = null,
        responseFormat: ResponseFormat = ResponseFormat.None,
    ): String {
        val jsonBody = buildJsonObject {
            put("model", modelId)
            put("stream", stream)
            // Ollama constrained decoding: "json" forces any valid JSON; a schema object
            // forces conformance to it (supported since Ollama 0.5).
            when (responseFormat) {
                is ResponseFormat.None -> {}
                is ResponseFormat.Json -> put("format", "json")
                is ResponseFormat.Schema -> put("format", responseFormat.schema)
            }
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                for (msg in messages) {
                    add(buildMessageObject(msg))
                }
            }
            putJsonObject("options") {
                put("num_ctx", NUM_CTX)
                put("temperature", TEMPERATURE)
            }
            if (!tools.isNullOrEmpty()) {
                putJsonArray("tools") {
                    for (tool in tools) {
                        add(
                            buildJsonObject {
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    putJsonObject("parameters") {
                                        for ((key, value) in tool.inputSchema) {
                                            put(key, value)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), jsonBody)
    }

    /**
     * Serializes a single chat message into Ollama's `/api/chat` wire format.
     * Assistant messages echo their `tool_calls`; TOOL results are their own
     * `role:"tool"` message carrying the tool name.
     */
    private fun buildMessageObject(msg: ChatMessage): JsonObject = buildJsonObject {
        when (msg.role) {
            Role.ASSISTANT -> {
                put("role", "assistant")
                put("content", msg.content)
                if (msg.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        for (call in msg.toolCalls) {
                            add(
                                buildJsonObject {
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        putJsonObject("arguments") {
                                            for ((key, value) in call.arguments) {
                                                put(key, value)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }

            Role.TOOL -> {
                put("role", "tool")
                msg.toolName?.let { put("tool_name", it) }
                put("content", msg.content)
            }

            Role.SYSTEM -> {
                put("role", "system")
                put("content", msg.content)
            }

            Role.USER -> {
                put("role", "user")
                put("content", msg.content)
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.Ollama"
        private const val DEFAULT_MODEL = "qwen2.5:7b"

        /**
         * Ollama defaults to a 4096-token context and silently truncates from the
         * front — dropping the system prompt and tool schemas. Raise it so the full
         * instruction set survives. Higher values cost RAM on the Ollama host.
         */
        private const val NUM_CTX = 8192
        private const val TEMPERATURE = 0.7
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * Fetch the list of models available on the Ollama server.
         * Auth config is applied since the proxy may protect all endpoints.
         */
        suspend fun fetchAvailableModels(
            client: OkHttpClient,
            serverUrl: String,
            authConfig: AuthConfig = AuthConfig.None,
        ): Result<List<OllamaModel>> = withContext(Dispatchers.IO) {
            val base = serverUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$base/api/tags")
                .get()
                .applyAuth(authConfig)
                .build()
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                    ?: return@withContext Result.failure(IOException("Empty response"))
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: $body"),
                    )
                }
                if (body.trimStart().startsWith("<")) {
                    return@withContext Result.failure(
                        IOException(
                            "Server returned HTML instead of JSON. " +
                                "If using Cloudflare Access, check your Service Token headers.",
                        ),
                    )
                }
                val jsonParser = Json { ignoreUnknownKeys = true }
                val parsed = jsonParser.parseToJsonElement(body).jsonObject
                val modelsArray = parsed["models"]?.jsonArray
                    ?: return@withContext Result.failure(
                        IOException("Unexpected response format: no 'models' array"),
                    )
                val models = mutableListOf<OllamaModel>()

                @Suppress("USELESS_CAST")
                val modelsList = modelsArray as List<JsonElement>
                for (i in modelsList.indices) {
                    val obj = modelsList[i].jsonObject
                    val details = obj["details"]?.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    models.add(
                        OllamaModel(
                            name = name,
                            size = obj["size"]?.jsonPrimitive?.long ?: 0L,
                            parameterSize = details?.get("parameter_size")?.jsonPrimitive?.contentOrNull,
                            quantizationLevel = details?.get("quantization_level")?.jsonPrimitive?.contentOrNull,
                        ),
                    )
                }
                Result.success(models)
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(IOException("Failed to parse response: ${e.message}", e))
            }
        }
    }
}

/** Summary of a model available on an Ollama server. */
data class OllamaModel(
    val name: String,
    val size: Long,
    val parameterSize: String? = null,
    val quantizationLevel: String? = null,
) {
    /** Human-readable size (e.g., "4.7 GB"). */
    val displaySize: String get() {
        val gb = size / 1_000_000_000.0
        return if (gb >= 1.0) {
            "%.1f GB".format(gb)
        } else {
            "%.0f MB".format(size / 1_000_000.0)
        }
    }

    /** Display string for UI: "llama3.1:8b — 4.7 GB" */
    val displayString: String get() = buildString {
        append(name)
        parameterSize?.let { append(" ($it)") }
        append(" — $displaySize")
    }
}
