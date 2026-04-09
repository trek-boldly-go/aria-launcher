package com.aria.launcher.aria.llm

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolUseBlock
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class ClaudeProvider(
    private val client: OkHttpClient,
    private val json: Json,
    private val token: String,
    override val modelId: String = DEFAULT_MODEL,
    private val isOAuth: Boolean = false,
    private val refreshToken: String? = null,
    private val onTokenRefreshed: ((newToken: String, newRefreshToken: String?) -> Unit)? = null,
) : LlmProvider {

    override val name: String = "Claude"

    private val isOAuthToken: Boolean get() = isOAuth || token.startsWith("sk-ant-oat01-")
    private var currentToken: String = token
    private var currentRefreshToken: String? = refreshToken
    private val refreshMutex = Mutex()

    private var anthropicClient: AnthropicClient = buildClient(currentToken)

    private fun buildClient(authToken: String): AnthropicClient {
        // The SDK's default JsonMapper registers jackson-module-kotlin, which uses
        // kotlin-reflect at runtime. kotlin-reflect crashes on Android because DEX
        // strips the Kotlin builtins metadata it needs. We supply our own JsonMapper
        // without the Kotlin module — the SDK's model classes use @JsonProperty
        // annotations and builders, so plain Jackson serializes them correctly.
        // Replicate the SDK's ObjectMappers.jsonMapper() config exactly, minus the
        // Kotlin module (which would pull in kotlin-reflect and crash on Android).
        // The SDK disables all auto-detection; its model classes use @JsonProperty
        // annotations exclusively, so this is safe.
        val mapper = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_ABSENT)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(SerializationFeature.FLUSH_AFTER_WRITE_VALUE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(MapperFeature.AUTO_DETECT_CREATORS)
            .disable(MapperFeature.AUTO_DETECT_FIELDS)
            .disable(MapperFeature.AUTO_DETECT_GETTERS)
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .disable(MapperFeature.AUTO_DETECT_SETTERS)
            .build()

        val builder = AnthropicOkHttpClient.builder()
            .jsonMapper(mapper)
        if (isOAuthToken) {
            builder.authToken(authToken)
            builder.putHeader("anthropic-beta", "oauth-2025-04-20")
        } else {
            builder.apiKey(authToken)
        }
        return builder.build()
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val params = buildParams(systemPrompt, messages, maxTokens)
        executeWithRetry(params)
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = callbackFlow {
        val params = buildParams(systemPrompt, messages, maxTokens)
        val job = launch(Dispatchers.IO) {
            try {
                anthropicClient.messages().createStreaming(params).use { stream ->
                    stream.stream().forEach { event ->
                        event.contentBlockDelta().ifPresent { deltaEvent ->
                            val delta = deltaEvent.delta()
                            if (delta.isText()) {
                                trySend(delta.asText().text())
                            }
                        }
                    }
                }
                close()
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { job.cancel() }
    }

    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val params = buildParams(systemPrompt, messages, maxTokens, tools)
        executeWithRetry(params)
    }

    private fun buildParams(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        tools: List<ToolDefinition>? = null,
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(maxTokens.toLong())
            .system(systemPrompt)

        for (msg in messages) {
            when (msg.role) {
                Role.ASSISTANT -> builder.addAssistantMessage(msg.content)
                else -> builder.addUserMessage(msg.content)
            }
        }

        if (tools != null) {
            for (tool in tools) {
                builder.addTool(mapTool(tool))
            }
        }

        return builder.build()
    }

    private fun mapTool(tool: ToolDefinition): Tool {
        val propsBuilder = Tool.InputSchema.Properties.builder()
        val schema = tool.inputSchema

        val properties = schema["properties"]
        if (properties is JsonObject) {
            for ((key, value) in properties) {
                propsBuilder.putAdditionalProperty(key, JsonValue.from(jsonElementToNative(value)))
            }
        }

        val schemaBuilder = Tool.InputSchema.builder()
            .properties(propsBuilder.build())

        val required = schema["required"]
        if (required is JsonArray) {
            for (item in required) {
                val name = (item as? JsonPrimitive)?.contentOrNull
                if (name != null) schemaBuilder.addRequired(name)
            }
        }

        return Tool.builder()
            .name(tool.name)
            .description(tool.description)
            .inputSchema(schemaBuilder.build())
            .build()
    }

    private fun parseResponse(message: Message): LlmResult {
        val textParts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()

        for (block in message.content()) {
            if (block.isText()) {
                textParts.add(block.asText().text())
            } else if (block.isToolUse()) {
                val toolUse: ToolUseBlock = block.asToolUse()
                toolCalls.add(
                    ToolCall(
                        id = toolUse.id(),
                        name = toolUse.name(),
                        arguments = jacksonToJsonElementMap(toolUse._input()),
                    ),
                )
            }
        }

        val text = textParts.joinToString("")
        return if (toolCalls.isNotEmpty()) {
            LlmResult.ToolUse(text, toolCalls)
        } else {
            LlmResult.Text(text)
        }
    }

    private suspend fun refreshOAuthToken(): Boolean = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val refresh = currentRefreshToken ?: return@withContext false
            try {
                val formBody = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refresh)
                    .add("client_id", OAUTH_CLIENT_ID)
                    .add("scope", OAUTH_SCOPES)
                    .build()
                val request = Request.Builder()
                    .url(TOKEN_REFRESH_URL)
                    .post(formBody)
                    .build()
                val response = client.newCall(request).await()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Token refresh HTTP ${response.code}")
                    return@withContext false
                }
                val responseBody = response.body?.string() ?: return@withContext false
                val parsed = json.parseToJsonElement(responseBody).jsonObject
                val newToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: return@withContext false
                val newRefresh = parsed["refresh_token"]?.jsonPrimitive?.contentOrNull
                currentToken = newToken
                if (newRefresh != null) currentRefreshToken = newRefresh
                anthropicClient = buildClient(newToken)
                onTokenRefreshed?.invoke(newToken, newRefresh ?: refresh)
                Log.d(TAG, "OAuth token refreshed successfully")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed", e)
                false
            }
        }
    }

    private suspend fun executeWithRetry(params: MessageCreateParams): LlmResult {
        return try {
            val message = anthropicClient.messages().create(params)
            parseResponse(message)
        } catch (e: UnauthorizedException) {
            if (isOAuthToken) {
                Log.d(TAG, "Got 401, attempting OAuth token refresh")
                if (refreshOAuthToken()) {
                    return try {
                        val message = anthropicClient.messages().create(params)
                        parseResponse(message)
                    } catch (retryEx: Exception) {
                        Log.e(TAG, "Retry after refresh failed", retryEx)
                        LlmResult.Error("Retry failed: ${retryEx.message}", retryEx)
                    }
                }
                LlmResult.Error("HTTP 401: Token expired and refresh failed")
            } else {
                LlmResult.Error("HTTP 401: Invalid API key", e)
            }
        } catch (e: IOException) {
            LlmResult.Error("Network error: ${e.message}", e)
        } catch (e: Exception) {
            LlmResult.Error("API error: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "ARIA.Claude"
        private const val TOKEN_REFRESH_URL = "https://platform.claude.com/v1/oauth/token"
        private const val OAUTH_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
        private const val OAUTH_SCOPES = "user:inference user:profile"
        private const val DEFAULT_MODEL = "claude-sonnet-4-20250514"
    }
}

private fun jsonElementToNative(element: JsonElement): Any? = when (element) {
    is JsonNull -> null

    is JsonPrimitive -> when {
        element.booleanOrNull != null -> element.boolean
        element.longOrNull != null -> element.long
        element.doubleOrNull != null -> element.double
        else -> element.contentOrNull
    }

    is JsonArray -> element.map { jsonElementToNative(it) }

    is JsonObject -> element.mapValues { (_, v) -> jsonElementToNative(v) }
}

private fun jacksonToJsonElement(value: JsonValue): JsonElement {
    return value.accept(object : JsonValue.Visitor<JsonElement> {
        override fun visitNull() = JsonNull
        override fun visitBoolean(value: Boolean) = JsonPrimitive(value)
        override fun visitNumber(value: Number) = JsonPrimitive(value)
        override fun visitString(value: String) = JsonPrimitive(value)
        override fun visitArray(values: List<JsonValue>) = JsonArray(values.map { jacksonToJsonElement(it) })
        override fun visitObject(values: Map<String, JsonValue>) = JsonObject(values.mapValues { (_, v) -> jacksonToJsonElement(v) })
        override fun visitMissing() = JsonNull
    })
}

private fun jacksonToJsonElementMap(value: JsonValue): Map<String, JsonElement> {
    val element = jacksonToJsonElement(value)
    return if (element is JsonObject) element.toMap() else emptyMap()
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
