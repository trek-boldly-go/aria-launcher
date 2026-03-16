package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

class ChatMessageTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ChatMessage construction and properties`() {
        val msg = ChatMessage(Role.USER, "Hello")
        assertThat(msg.role).isEqualTo(Role.USER)
        assertThat(msg.content).isEqualTo("Hello")
    }

    @Test
    fun `Role enum has expected values`() {
        assertThat(Role.values().toList()).containsExactly(Role.USER, Role.ASSISTANT, Role.SYSTEM)
    }

    @Test
    fun `ToolDefinition serialization roundtrip`() {
        val tool = ToolDefinition(
            name = "test_tool",
            description = "A test tool",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("param1") {
                        put("type", "string")
                        put("description", "A parameter")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("param1"))),
            ),
        )
        val serialized = json.encodeToString(ToolDefinition.serializer(), tool)
        val deserialized = json.decodeFromString(ToolDefinition.serializer(), serialized)
        assertThat(deserialized).isEqualTo(tool)
    }

    @Test
    fun `ToolCall serialization roundtrip`() {
        val call = ToolCall(
            id = "call_123",
            name = "open_app",
            arguments = mapOf(
                "package_name" to JsonPrimitive("com.spotify.music"),
            ),
        )
        val serialized = json.encodeToString(ToolCall.serializer(), call)
        val deserialized = json.decodeFromString(ToolCall.serializer(), serialized)
        assertThat(deserialized).isEqualTo(call)
    }

    @Test
    fun `ToolCall with empty arguments`() {
        val call = ToolCall(id = "call_1", name = "no_args", arguments = emptyMap())
        val serialized = json.encodeToString(ToolCall.serializer(), call)
        val deserialized = json.decodeFromString(ToolCall.serializer(), serialized)
        assertThat(deserialized.arguments).isEmpty()
    }

    @Test
    fun `LlmResult sealed class variants`() {
        val text = LlmResult.Text("Hello")
        assertThat(text.content).isEqualTo("Hello")

        val toolCall = ToolCall("id", "name", emptyMap())
        val toolUse = LlmResult.ToolUse("thinking", listOf(toolCall))
        assertThat(toolUse.content).isEqualTo("thinking")
        assertThat(toolUse.toolCalls).hasSize(1)

        val error = LlmResult.Error("something broke", RuntimeException("cause"))
        assertThat(error.message).isEqualTo("something broke")
        assertThat(error.cause).isInstanceOf(RuntimeException::class.java)

        val errorNoCause = LlmResult.Error("no cause")
        assertThat(errorNoCause.cause).isNull()
    }
}
