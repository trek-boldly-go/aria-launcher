package com.aria.launcher.aria.llm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AriaPromptsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ariaTools contains all expected tool names`() {
        val toolNames = AriaPrompts.ariaTools.map { it.name }
        assertThat(toolNames).containsAtLeast(
            "open_app", "search_web", "set_reminder", "get_directions", "compose_message",
            "lookup_contact", "get_calendar_events", "get_current_location", "list_apps",
            "get_weather", "remember", "forget",
        )
    }

    @Test
    fun `each tool has non-empty description`() {
        for (tool in AriaPrompts.ariaTools) {
            assertThat(tool.description).isNotEmpty()
        }
    }

    @Test
    fun `each tool has properties and required in inputSchema`() {
        for (tool in AriaPrompts.ariaTools) {
            assertThat(tool.inputSchema).containsKey("properties")
            assertThat(tool.inputSchema).containsKey("required")
        }
    }

    @Test
    fun `tool definitions serialize to valid JSON`() {
        for (tool in AriaPrompts.ariaTools) {
            val serialized = json.encodeToString(ToolDefinition.serializer(), tool)
            val parsed = json.parseToJsonElement(serialized).jsonObject
            assertThat(parsed["name"]?.jsonPrimitive?.content).isEqualTo(tool.name)
            assertThat(parsed["description"]?.jsonPrimitive?.content).isEqualTo(tool.description)
            assertThat(parsed["inputSchema"]).isNotNull()
        }
    }

    @Test
    fun `open_app tool requires package_name`() {
        val openApp = AriaPrompts.ariaTools.find { it.name == "open_app" }!!
        val required = openApp.inputSchema["required"]!!.jsonArray
        assertThat(required.map { it.jsonPrimitive.content }).contains("package_name")
    }

    @Test
    fun `set_reminder tool requires message and time`() {
        val reminder = AriaPrompts.ariaTools.find { it.name == "set_reminder" }!!
        val required = reminder.inputSchema["required"]!!.jsonArray
        val requiredNames = required.map { it.jsonPrimitive.content }
        assertThat(requiredNames).containsAtLeast("message", "time")
    }

    @Test
    fun `compose_message tool requires contact and message`() {
        val compose = AriaPrompts.ariaTools.find { it.name == "compose_message" }!!
        val required = compose.inputSchema["required"]!!.jsonArray
        val requiredNames = required.map { it.jsonPrimitive.content }
        assertThat(requiredNames).containsAtLeast("contact", "message")
    }

    @Test
    fun `buildCardCurationPrompt includes skill results`() {
        val prompt = AriaPrompts.buildCardCurationPrompt(
            listOf("skill_1: result A", "skill_2: result B"),
        )
        assertThat(prompt).contains("skill_1: result A")
        assertThat(prompt).contains("skill_2: result B")
        assertThat(prompt).contains("JSON array")
    }
}
