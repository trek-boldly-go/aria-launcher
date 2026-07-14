package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class OpenAICompatibleProviderTest {

    private val client = mock<OkHttpClient>()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `complete parses text response`() = runTest {
        val provider = makeProvider()
        mockExecuteResponse(
            200,
            """{
                "choices": [{
                    "message": {"role": "assistant", "content": "Hello from GPT!"},
                    "finish_reason": "stop"
                }]
            }""",
        )

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
        assertThat((result as LlmResult.Text).content).isEqualTo("Hello from GPT!")
    }

    @Test
    fun `complete returns error on empty choices`() = runTest {
        val provider = makeProvider()
        mockExecuteResponse(200, """{"choices":[]}""")

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
    }

    @Test
    fun `complete returns error on HTTP failure`() = runTest {
        val provider = makeProvider()
        mockExecuteResponse(429, """{"error":"rate limited"}""")

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
        assertThat((result as LlmResult.Error).message).contains("429")
    }

    @Test
    fun `completeWithTools parses tool calls`() = runTest {
        val provider = makeProvider()
        mockExecuteResponse(
            200,
            """{
                "choices": [{
                    "message": {
                        "role": "assistant",
                        "content": "Opening app",
                        "tool_calls": [{
                            "id": "call_abc",
                            "type": "function",
                            "function": {
                                "name": "open_app",
                                "arguments": "{\"package_name\":\"com.spotify.music\"}"
                            }
                        }]
                    },
                    "finish_reason": "tool_calls"
                }]
            }""",
        )

        val tools = listOf(
            ToolDefinition("open_app", "Open an app", emptyMap()),
        )
        val result = provider.completeWithTools(
            "system", listOf(ChatMessage(Role.USER, "open Spotify")), tools,
        )

        assertThat(result).isInstanceOf(LlmResult.ToolUse::class.java)
        val toolUse = result as LlmResult.ToolUse
        assertThat(toolUse.content).isEqualTo("Opening app")
        assertThat(toolUse.toolCalls).hasSize(1)
        assertThat(toolUse.toolCalls[0].name).isEqualTo("open_app")
        assertThat(toolUse.toolCalls[0].id).isEqualTo("call_abc")
    }

    @Test
    fun `completeWithTools returns text when no tool calls`() = runTest {
        val provider = makeProvider()
        mockExecuteResponse(
            200,
            """{
                "choices": [{
                    "message": {"role": "assistant", "content": "No tools needed"},
                    "finish_reason": "stop"
                }]
            }""",
        )

        val tools = listOf(ToolDefinition("open_app", "Open an app", emptyMap()))
        val result = provider.completeWithTools(
            "system", listOf(ChatMessage(Role.USER, "hello")), tools,
        )

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
    }

    @Test
    fun `assistant tool_calls serialize with stringified arguments and tool result role`() = runTest {
        val provider = makeProvider()
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            requestCaptor,
        )

        val call = ToolCall(
            id = "call_abc",
            name = "open_app",
            arguments = mapOf("package_name" to JsonPrimitive("com.spotify.music")),
        )
        val messages = listOf(
            ChatMessage(Role.USER, "open Spotify"),
            ChatMessage(Role.ASSISTANT, "opening", toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, "launched", toolCallId = call.id, toolName = call.name),
        )
        provider.complete("system", messages)

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }

        val assistant = msgs.first { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" }
        val toolCall = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertThat(toolCall["id"]?.jsonPrimitive?.contentOrNull).isEqualTo("call_abc")
        assertThat(toolCall["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("function")
        val fn = toolCall["function"]!!.jsonObject
        assertThat(fn["name"]?.jsonPrimitive?.contentOrNull).isEqualTo("open_app")
        // OpenAI requires arguments as a JSON *string*, not an object.
        val argsRaw = fn["arguments"]!!.jsonPrimitive.contentOrNull!!
        val argsParsed = json.parseToJsonElement(argsRaw).jsonObject
        assertThat(argsParsed["package_name"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("com.spotify.music")

        val toolMsg = msgs.first { it["role"]?.jsonPrimitive?.contentOrNull == "tool" }
        assertThat(toolMsg["tool_call_id"]?.jsonPrimitive?.contentOrNull).isEqualTo("call_abc")
        assertThat(toolMsg["content"]?.jsonPrimitive?.contentOrNull).isEqualTo("launched")
    }

    // ── Structured output tests ──

    @Test
    fun `responseFormat Json sets response_format json_object`() = runTest {
        val provider = makeProvider()
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}""",
            requestCaptor,
        )

        provider.complete(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            responseFormat = ResponseFormat.Json,
        )

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val rf = body["response_format"]!!.jsonObject
        assertThat(rf["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("json_object")
    }

    @Test
    fun `responseFormat Schema sets response_format json_schema`() = runTest {
        val provider = makeProvider()
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"choices":[{"message":{"content":"{}"},"finish_reason":"stop"}]}""",
            requestCaptor,
        )

        val schema = json.parseToJsonElement(
            """{"type":"object","properties":{"ok":{"type":"boolean"}}}""",
        ).jsonObject
        provider.complete(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            responseFormat = ResponseFormat.Schema(schema),
        )

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val rf = body["response_format"]!!.jsonObject
        assertThat(rf["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("json_schema")
        val jsonSchema = rf["json_schema"]!!.jsonObject
        assertThat(jsonSchema["schema"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("object")
    }

    @Test
    fun `retries without response_format on HTTP 400 naming the field`() = runTest {
        val provider = makeProvider()
        val requestCaptor = argumentCaptor<Request>()
        val mockCall = mock<Call>()
        whenever(client.newCall(requestCaptor.capture())).thenReturn(mockCall)
        whenever(mockCall.execute())
            .thenReturn(
                buildResponse(400, """{"error":{"message":"Unsupported parameter: 'response_format'"}}"""),
            )
            .thenReturn(
                buildResponse(200, """{"choices":[{"message":{"content":"recovered"},"finish_reason":"stop"}]}"""),
            )

        val result = provider.complete(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            responseFormat = ResponseFormat.Json,
        )

        // The retry succeeds and its body drops the response_format field.
        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
        assertThat((result as LlmResult.Text).content).isEqualTo("recovered")
        assertThat(requestCaptor.allValues).hasSize(2)
        val firstBody = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val secondBody = json.parseToJsonElement(requestCaptor.secondValue.bodyString()).jsonObject
        assertThat(firstBody.containsKey("response_format")).isTrue()
        assertThat(secondBody.containsKey("response_format")).isFalse()
    }

    @Test
    fun `retries without response_format on a non-400 failure that omits the field name`() = runTest {
        // Broad guard: backends reject the field with varied statuses and generic bodies
        // (here 422 with no mention of response_format). The retry must still fire.
        val provider = makeProvider()
        val requestCaptor = argumentCaptor<Request>()
        val mockCall = mock<Call>()
        whenever(client.newCall(requestCaptor.capture())).thenReturn(mockCall)
        whenever(mockCall.execute())
            .thenReturn(buildResponse(422, """{"error":"Extra inputs are not permitted"}"""))
            .thenReturn(
                buildResponse(200, """{"choices":[{"message":{"content":"recovered"},"finish_reason":"stop"}]}"""),
            )

        val result = provider.complete(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            responseFormat = ResponseFormat.Json,
        )

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
        assertThat((result as LlmResult.Text).content).isEqualTo("recovered")
        assertThat(requestCaptor.allValues).hasSize(2)
        val secondBody = json.parseToJsonElement(requestCaptor.secondValue.bodyString()).jsonObject
        assertThat(secondBody.containsKey("response_format")).isFalse()
    }

    @Test
    fun `does not retry when no responseFormat was requested`() = runTest {
        val provider = makeProvider()
        val requestCaptor = argumentCaptor<Request>()
        val mockCall = mock<Call>()
        whenever(client.newCall(requestCaptor.capture())).thenReturn(mockCall)
        whenever(mockCall.execute())
            .thenReturn(buildResponse(400, """{"error":{"message":"context length exceeded"}}"""))

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
        assertThat(requestCaptor.allValues).hasSize(1)
    }

    @Test
    fun `request URL includes v1 chat completions path`() = runTest {
        val provider = makeProvider(baseUrl = "https://api.openai.com")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            requestCaptor,
        )

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(requestCaptor.firstValue.url.toString())
            .isEqualTo("https://api.openai.com/v1/chat/completions")
    }

    @Test
    fun `Bearer auth header is set`() = runTest {
        val provider = makeProvider(apiKey = "sk-test-key")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            requestCaptor,
        )

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(requestCaptor.firstValue.header("Authorization")).isEqualTo("Bearer sk-test-key")
    }

    @Test
    fun `gemini factory sets correct base URL and model`() {
        val gemini = OpenAICompatibleProvider.gemini(client, json, "test-key")
        assertThat(gemini.name).isEqualTo("Gemini")
        assertThat(gemini.modelId).isEqualTo("gemini-2.0-flash")
    }

    @Test
    fun `openRouter factory sets correct defaults`() {
        val router = OpenAICompatibleProvider.openRouter(client, json, "test-key")
        assertThat(router.name).isEqualTo("OpenRouter")
        assertThat(router.modelId).isEqualTo("anthropic/claude-sonnet-4")
    }

    private fun makeProvider(
        baseUrl: String = "https://api.openai.com",
        apiKey: String = "sk-test",
        modelId: String = "gpt-4",
    ) = OpenAICompatibleProvider(client, json, baseUrl, apiKey, modelId)

    private fun Request.bodyString(): String {
        val buffer = okio.Buffer()
        body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun mockExecuteResponse(
        code: Int,
        body: String,
        requestCaptor: org.mockito.kotlin.KArgumentCaptor<Request>? = null,
    ) {
        val mockCall = mock<Call>()
        if (requestCaptor != null) {
            whenever(client.newCall(requestCaptor.capture())).thenReturn(mockCall)
        } else {
            whenever(client.newCall(any())).thenReturn(mockCall)
        }
        whenever(mockCall.execute()).thenReturn(buildResponse(code, body))
    }

    private fun buildResponse(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url("https://api.openai.com/v1/chat/completions").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
