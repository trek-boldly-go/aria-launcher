package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test

class LiteRtToolPromptTest {

    private fun tool(
        name: String,
        description: String,
        properties: List<String> = emptyList(),
        required: List<String> = emptyList(),
    ) = ToolDefinition(
        name = name,
        description = description,
        inputSchema = mapOf(
            "properties" to buildJsonObject {
                for (p in properties) putJsonObject(p) { put("type", "string") }
            },
            "required" to JsonArray(required.map { JsonPrimitive(it) }),
        ),
    )

    @Test
    fun `renderToolInstructions is empty when no tools`() {
        assertThat(LiteRtToolPrompt.renderToolInstructions(emptyList())).isEmpty()
    }

    @Test
    fun `renderToolInstructions lists names, collapsed descriptions and params`() {
        val rendered = LiteRtToolPrompt.renderToolInstructions(
            listOf(
                tool(
                    name = "fetch_url",
                    description = "Fetch data\n  from a URL",
                    properties = listOf("url", "method"),
                    required = listOf("url"),
                ),
            ),
        )
        assertThat(rendered).contains("- fetch_url: Fetch data from a URL")
        assertThat(rendered).contains("params: url*, method")
        assertThat(rendered).contains("\"tool\": \"<name>\"")
    }

    @Test
    fun `parseReply returns invocation for pure JSON`() {
        val reply = LiteRtToolPrompt.parseReply(
            """{"tool": "open_app", "arguments": {"package_name": "com.spotify.music"}}""",
        )
        assertThat(reply).isInstanceOf(LiteRtToolPrompt.Reply.Invocation::class.java)
        val inv = reply as LiteRtToolPrompt.Reply.Invocation
        assertThat(inv.name).isEqualTo("open_app")
        assertThat(inv.arguments["package_name"]?.jsonPrimitive?.content).isEqualTo("com.spotify.music")
    }

    @Test
    fun `parseReply tolerates code fences and surrounding prose`() {
        val reply = LiteRtToolPrompt.parseReply(
            "Sure!\n```json\n{\"tool\": \"search_web\", \"arguments\": {\"query\": \"weather\"}}\n```",
        )
        assertThat(reply).isInstanceOf(LiteRtToolPrompt.Reply.Invocation::class.java)
        assertThat((reply as LiteRtToolPrompt.Reply.Invocation).name).isEqualTo("search_web")
    }

    @Test
    fun `parseReply accepts missing arguments`() {
        val reply = LiteRtToolPrompt.parseReply("""{"tool": "list_apps"}""")
        assertThat(reply).isInstanceOf(LiteRtToolPrompt.Reply.Invocation::class.java)
        val inv = reply as LiteRtToolPrompt.Reply.Invocation
        assertThat(inv.name).isEqualTo("list_apps")
        assertThat(inv.arguments).isEmpty()
    }

    @Test
    fun `parseReply returns plain text for a normal answer`() {
        val text = "The weather looks clear this afternoon."
        val reply = LiteRtToolPrompt.parseReply(text)
        assertThat(reply).isEqualTo(LiteRtToolPrompt.Reply.PlainText(text))
    }

    @Test
    fun `parseReply returns plain text when JSON lacks a tool name`() {
        val text = """{"result": "ok"}"""
        assertThat(LiteRtToolPrompt.parseReply(text)).isEqualTo(LiteRtToolPrompt.Reply.PlainText(text))
    }

    @Test
    fun `parseReply treats a blank tool name as plain text`() {
        val text = """{"tool": "  ", "arguments": {}}"""
        assertThat(LiteRtToolPrompt.parseReply(text)).isEqualTo(LiteRtToolPrompt.Reply.PlainText(text))
    }

    @Test
    fun `contentForMessage returns content when present`() {
        val msg = ChatMessage(Role.USER, "Hello there")
        assertThat(LiteRtToolPrompt.contentForMessage(msg)).isEqualTo("Hello there")
    }

    @Test
    fun `contentForMessage renders assistant tool call when content is blank`() {
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            content = "",
            toolCalls = listOf(
                ToolCall("id1", "open_app", mapOf("package_name" to JsonPrimitive("com.foo"))),
            ),
        )
        val rendered = LiteRtToolPrompt.contentForMessage(msg)

        // Round-trips back into an invocation the model can follow.
        val reparsed = LiteRtToolPrompt.parseReply(rendered)
        assertThat(reparsed).isInstanceOf(LiteRtToolPrompt.Reply.Invocation::class.java)
        val inv = reparsed as LiteRtToolPrompt.Reply.Invocation
        assertThat(inv.name).isEqualTo("open_app")
        assertThat(inv.arguments["package_name"]?.jsonPrimitive?.content).isEqualTo("com.foo")
    }

    @Test
    fun `contentForMessage returns blank when no content and no tool calls`() {
        val msg = ChatMessage(Role.ASSISTANT, "")
        assertThat(LiteRtToolPrompt.contentForMessage(msg)).isEmpty()
    }

    @Test
    fun `contentForMessage folds a TOOL result into labelled user prose`() {
        val msg = ChatMessage(
            role = Role.TOOL,
            content = "72F and clear",
            toolCallId = "id1",
            toolName = "get_weather",
        )
        assertThat(LiteRtToolPrompt.contentForMessage(msg))
            .isEqualTo("Tool result (get_weather): 72F and clear")
    }

    @Test
    fun `renderToolResult omits the label when the tool name is absent`() {
        val msg = ChatMessage(Role.TOOL, "done", toolCallId = "id1")
        assertThat(LiteRtToolPrompt.renderToolResult(msg)).isEqualTo("Tool result: done")
    }
}
