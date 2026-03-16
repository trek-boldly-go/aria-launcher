package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
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

class ClaudeProviderTest {

    private val client = mock<OkHttpClient>()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `complete parses text response`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-test-key")
        mockEnqueueResponse(
            200,
            """{"content":[{"type":"text","text":"Hello from Claude!"}]}""",
        )

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
        assertThat((result as LlmResult.Text).content).isEqualTo("Hello from Claude!")
    }

    @Test
    fun `complete parses tool use response`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-test-key")
        mockEnqueueResponse(
            200,
            """{
                "content": [
                    {"type":"text","text":"Let me open that for you."},
                    {"type":"tool_use","id":"call_1","name":"open_app","input":{"package_name":"com.spotify.music"}}
                ]
            }""",
        )

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "open Spotify")))

        assertThat(result).isInstanceOf(LlmResult.ToolUse::class.java)
        val toolUse = result as LlmResult.ToolUse
        assertThat(toolUse.content).isEqualTo("Let me open that for you.")
        assertThat(toolUse.toolCalls).hasSize(1)
        assertThat(toolUse.toolCalls[0].name).isEqualTo("open_app")
        assertThat(toolUse.toolCalls[0].id).isEqualTo("call_1")
    }

    @Test
    fun `complete returns error on HTTP failure`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-test-key")
        mockEnqueueResponse(500, """{"error":"Internal server error"}""")

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
        assertThat((result as LlmResult.Error).message).contains("500")
    }

    @Test
    fun `complete returns error on empty content array`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-test-key")
        mockEnqueueResponse(200, """{"id":"msg_1"}""")

        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
    }

    @Test
    fun `API key auth uses x-api-key header`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-test-key")
        val requestCaptor = argumentCaptor<Request>()
        mockEnqueueResponse(200, """{"content":[{"type":"text","text":"ok"}]}""", requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        assertThat(request.header("x-api-key")).isEqualTo("sk-ant-api-test-key")
        assertThat(request.header("Authorization")).isNull()
        assertThat(request.header("anthropic-version")).isEqualTo("2023-06-01")
    }

    @Test
    fun `OAuth token uses Bearer auth`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-oat01-test-oauth-token")
        val requestCaptor = argumentCaptor<Request>()
        mockEnqueueResponse(200, """{"content":[{"type":"text","text":"ok"}]}""", requestCaptor)

        provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))

        val request = requestCaptor.firstValue
        assertThat(request.header("Authorization")).isEqualTo("Bearer sk-ant-oat01-test-oauth-token")
        assertThat(request.header("x-api-key")).isNull()
    }

    private fun mockEnqueueResponse(
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
        whenever(mockCall.enqueue(any())).thenAnswer { invocation ->
            val callback = invocation.getArgument<Callback>(0)
            val response = Response.Builder()
                .request(Request.Builder().url("https://api.anthropic.com/v1/messages").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
            callback.onResponse(mockCall, response)
        }
    }
}
