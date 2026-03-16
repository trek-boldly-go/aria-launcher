package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
            .request(Request.Builder().url("https://api.openai.com/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        whenever(mockCall.execute()).thenReturn(response)
    }
}
