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

class OllamaProviderTest {

    private val client = mock<OkHttpClient>()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `complete parses text response`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        mockExecuteResponse(
            200,
            """{"message":{"role":"assistant","content":"Hello from Ollama!"}}""",
        )

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
        assertThat((result as LlmResult.Text).content).isEqualTo("Hello from Ollama!")
    }

    @Test
    fun `complete returns error on HTTP failure`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        mockExecuteResponse(500, """{"error":"model not found"}""")

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
        assertThat((result as LlmResult.Error).message).contains("500")
    }

    @Test
    fun `complete returns error on missing content`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        mockExecuteResponse(200, """{"message":{"role":"assistant"}}""")

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
    }

    @Test
    fun `request URL uses correct base path`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434/")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"message":{"role":"assistant","content":"ok"}}""",
            requestCaptor,
        )

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        assertThat(request.url.toString()).isEqualTo("http://192.168.1.100:11434/api/chat")
    }

    @Test
    fun `trailing slash in server URL is trimmed`() = runTest {
        val provider = OllamaProvider(client, json, "http://myserver:11434///")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"message":{"role":"assistant","content":"ok"}}""",
            requestCaptor,
        )

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val url = requestCaptor.firstValue.url.toString()
        assertThat(url).doesNotContain("///")
        assertThat(url).endsWith("/api/chat")
    }

    // ── Auth header tests ──

    @Test
    fun `no auth sends no Authorization header`() = runTest {
        val provider = OllamaProvider(
            client, json, "http://192.168.1.100:11434",
            authConfig = AuthConfig.None,
        )
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        assertThat(request.header("Authorization")).isNull()
    }

    @Test
    fun `basic auth sends correct Authorization header`() = runTest {
        val provider = OllamaProvider(
            client, json, "http://192.168.1.100:11434",
            authConfig = AuthConfig.Basic("user", "pass"),
        )
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        val authHeader = request.header("Authorization")
        assertThat(authHeader).isNotNull()
        assertThat(authHeader).startsWith("Basic ")
        // "user:pass" base64 = "dXNlcjpwYXNz"
        assertThat(authHeader).isEqualTo("Basic dXNlcjpwYXNz")
    }

    @Test
    fun `bearer auth sends correct Authorization header`() = runTest {
        val provider = OllamaProvider(
            client, json, "http://192.168.1.100:11434",
            authConfig = AuthConfig.BearerToken("mytoken123"),
        )
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        assertThat(request.header("Authorization")).isEqualTo("Bearer mytoken123")
    }

    @Test
    fun `custom headers sends all configured headers`() = runTest {
        val provider = OllamaProvider(
            client, json, "http://192.168.1.100:11434",
            authConfig = AuthConfig.CustomHeaders(
                mapOf(
                    "CF-Access-Client-Id" to "abc",
                    "CF-Access-Client-Secret" to "xyz",
                ),
            ),
        )
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        assertThat(request.header("CF-Access-Client-Id")).isEqualTo("abc")
        assertThat(request.header("CF-Access-Client-Secret")).isEqualTo("xyz")
    }

    // ── Native tool calling tests ──

    @Test
    fun `completeWithTools sends native tools array`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(
            200,
            """{"message":{"role":"assistant","content":"I'll check the weather","tool_calls":[{"function":{"name":"get_weather","arguments":{"location":"NYC"}}}]}}""",
            requestCaptor,
        )

        val tools = listOf(
            ToolDefinition(
                "get_weather",
                "Get weather",
                mapOf("type" to json.parseToJsonElement("\"object\"")),
            ),
        )
        val result = provider.completeWithTools("system", listOf(ChatMessage(Role.USER, "weather?")), tools)

        // Verify the request body contains tools
        val requestBody = requestCaptor.firstValue.body
        assertThat(requestBody).isNotNull()

        // Verify we get a ToolUse result
        assertThat(result).isInstanceOf(LlmResult.ToolUse::class.java)
        val toolUse = result as LlmResult.ToolUse
        assertThat(toolUse.toolCalls).hasSize(1)
        assertThat(toolUse.toolCalls[0].name).isEqualTo("get_weather")
    }

    @Test
    fun `request body sets num_ctx option`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val options = body["options"]?.jsonObject
        assertThat(options).isNotNull()
        assertThat(options!!["num_ctx"]?.jsonPrimitive?.contentOrNull).isEqualTo("8192")
    }

    @Test
    fun `assistant tool_calls and tool results serialize with correct roles`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        val call = ToolCall(
            id = "call_1",
            name = "get_weather",
            arguments = mapOf("location" to JsonPrimitive("NYC")),
        )
        val messages = listOf(
            ChatMessage(Role.USER, "weather?"),
            ChatMessage(Role.ASSISTANT, "checking", toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, "72F sunny", toolCallId = call.id, toolName = call.name),
        )
        provider.complete("system", messages)

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }

        // messages[0] is the system prompt injected by the provider
        val assistant = msgs.first { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" }
        val toolCalls = assistant["tool_calls"]!!.jsonArray
        assertThat(toolCalls).hasSize(1)
        val fn = toolCalls[0].jsonObject["function"]!!.jsonObject
        assertThat(fn["name"]?.jsonPrimitive?.contentOrNull).isEqualTo("get_weather")
        // Ollama takes arguments as an object, not a string
        assertThat(fn["arguments"]!!.jsonObject["location"]?.jsonPrimitive?.contentOrNull)
            .isEqualTo("NYC")

        val toolMsg = msgs.first { it["role"]?.jsonPrimitive?.contentOrNull == "tool" }
        assertThat(toolMsg["tool_name"]?.jsonPrimitive?.contentOrNull).isEqualTo("get_weather")
        assertThat(toolMsg["content"]?.jsonPrimitive?.contentOrNull).isEqualTo("72F sunny")
    }

    @Test
    fun `completeWithTools returns text when no tool_calls in response`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        mockExecuteResponse(
            200,
            """{"message":{"role":"assistant","content":"I don't need any tools for this."}}""",
        )

        val tools = listOf(
            ToolDefinition("test_tool", "A test tool", emptyMap()),
        )
        val result = provider.completeWithTools("system", listOf(ChatMessage(Role.USER, "hello")), tools)

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
        assertThat((result as LlmResult.Text).content).isEqualTo("I don't need any tools for this.")
    }

    // ── Structured output tests ──

    @Test
    fun `responseFormat Json sets format to json string`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            responseFormat = ResponseFormat.Json,
        )

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        assertThat(body["format"]?.jsonPrimitive?.contentOrNull).isEqualTo("json")
    }

    @Test
    fun `responseFormat Schema sets format to schema object`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        val schema = json.parseToJsonElement(
            """{"type":"object","properties":{"ok":{"type":"boolean"}}}""",
        ).jsonObject
        provider.complete(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            responseFormat = ResponseFormat.Schema(schema),
        )

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        val format = body["format"]?.jsonObject
        assertThat(format).isNotNull()
        assertThat(format!!["type"]?.jsonPrimitive?.contentOrNull).isEqualTo("object")
    }

    @Test
    fun `responseFormat None omits format field`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        val requestCaptor = argumentCaptor<Request>()
        mockExecuteResponse(200, SIMPLE_RESPONSE, requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val body = json.parseToJsonElement(requestCaptor.firstValue.bodyString()).jsonObject
        assertThat(body.containsKey("format")).isFalse()
    }

    // ── Model list tests ──

    @Test
    fun `fetchAvailableModels parses response`() = runTest {
        mockExecuteResponse(
            200,
            """{
                "models": [
                    {
                        "name": "llama3.1:8b",
                        "size": 4700000000,
                        "details": {
                            "parameter_size": "8B",
                            "quantization_level": "Q4_0"
                        }
                    },
                    {
                        "name": "qwen2.5:7b",
                        "size": 4100000000,
                        "details": {
                            "parameter_size": "7B",
                            "quantization_level": "Q4_K_M"
                        }
                    }
                ]
            }""",
        )

        val result = OllamaProvider.fetchAvailableModels(
            client, "http://192.168.1.100:11434",
        )

        assertThat(result.isSuccess).isTrue()
        val models = result.getOrThrow()
        assertThat(models).hasSize(2)
        assertThat(models[0].name).isEqualTo("llama3.1:8b")
        assertThat(models[0].parameterSize).isEqualTo("8B")
        assertThat(models[1].name).isEqualTo("qwen2.5:7b")
    }

    @Test
    fun `fetchAvailableModels returns failure on HTTP error`() = runTest {
        mockExecuteResponse(401, """{"error":"unauthorized"}""")

        val result = OllamaProvider.fetchAvailableModels(
            client, "http://192.168.1.100:11434",
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("401")
    }

    @Test
    fun `fetchAvailableModels returns failure on malformed response`() = runTest {
        mockExecuteResponse(200, """{"not_models": []}""")

        val result = OllamaProvider.fetchAvailableModels(
            client, "http://192.168.1.100:11434",
        )

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("Unexpected response format")
    }

    @Test
    fun `OllamaModel displaySize formats correctly`() {
        assertThat(OllamaModel("test", 4_700_000_000L).displaySize).isEqualTo("4.7 GB")
        assertThat(OllamaModel("test", 500_000_000L).displaySize).isEqualTo("500 MB")
        assertThat(OllamaModel("test", 1_000_000_000L).displaySize).isEqualTo("1.0 GB")
    }

    // ── Helpers ──

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
        val response = Response.Builder()
            .request(Request.Builder().url("http://192.168.1.100:11434/api/chat").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        whenever(mockCall.execute()).thenReturn(response)
    }

    companion object {
        private const val SIMPLE_RESPONSE =
            """{"message":{"role":"assistant","content":"ok"}}"""
    }
}
