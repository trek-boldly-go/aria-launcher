package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * Tests for [ClaudeProvider].
 *
 * After the migration to the Anthropic Java SDK, the provider no longer makes raw
 * OkHttp calls for the Messages API — the SDK manages its own HTTP client internally.
 * These tests verify construction, configuration, and error handling at the provider
 * boundary. Integration-level tests against real/mock API endpoints belong in
 * instrumented tests.
 */
class ClaudeProviderTest {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `provider construction with API key`() {
        val provider = ClaudeProvider(client, json, "sk-ant-api-test-key")
        assertThat(provider.name).isEqualTo("Claude")
        assertThat(provider.modelId).isEqualTo("claude-sonnet-4-20250514")
    }

    @Test
    fun `provider construction with OAuth token`() {
        val provider = ClaudeProvider(
            client,
            json,
            "sk-ant-oat01-test-oauth-token",
            isOAuth = true,
        )
        assertThat(provider.name).isEqualTo("Claude")
    }

    @Test
    fun `provider construction with custom model`() {
        val provider = ClaudeProvider(
            client,
            json,
            "sk-ant-api-test-key",
            modelId = "claude-haiku-4-5-20251001",
        )
        assertThat(provider.modelId).isEqualTo("claude-haiku-4-5-20251001")
    }

    @Test
    fun `complete returns error for invalid API key`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-invalid")
        val result = provider.complete("system", listOf(ChatMessage(Role.USER, "hello")))
        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
    }

    @Test
    fun `completeWithTools returns error for invalid API key`() = runTest {
        val provider = ClaudeProvider(client, json, "sk-ant-api-invalid")
        val tools = listOf(
            ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                inputSchema = mapOf(),
            ),
        )
        val result = provider.completeWithTools(
            "system",
            listOf(ChatMessage(Role.USER, "hello")),
            tools,
        )
        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
    }

    @Test
    fun `tool call history is built into content blocks without throwing`() = runTest {
        // Exercises buildParams with an assistant tool_use block followed by a
        // tool_result block. A malformed conversion would throw here; a valid one
        // surfaces as an auth Error once it reaches the network.
        val provider = ClaudeProvider(client, json, "sk-ant-api-invalid")
        val call = ToolCall(
            id = "toolu_1",
            name = "get_weather",
            arguments = mapOf("location" to JsonPrimitive("NYC")),
        )
        val messages = listOf(
            ChatMessage(Role.USER, "weather?"),
            ChatMessage(Role.ASSISTANT, "checking", toolCalls = listOf(call)),
            ChatMessage(Role.TOOL, "72F sunny", toolCallId = call.id, toolName = call.name),
        )
        val tools = listOf(ToolDefinition("get_weather", "Get weather", mapOf()))

        val result = provider.completeWithTools("system", messages, tools)

        assertThat(result).isInstanceOf(LlmResult.Error::class.java)
    }
}
