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

    @Test
    fun `completeWithTools falls back to tool descriptions in system prompt`() = runTest {
        val provider = OllamaProvider(client, json, "http://192.168.1.100:11434")
        mockExecuteResponse(
            200,
            """{"message":{"role":"assistant","content":"I'll use the tool"}}""",
        )

        val tools = listOf(
            ToolDefinition("test_tool", "A test tool", emptyMap()),
        )
        val result = provider.completeWithTools("system", listOf(ChatMessage(Role.USER, "hello")), tools)

        assertThat(result).isInstanceOf(LlmResult.Text::class.java)
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
}
