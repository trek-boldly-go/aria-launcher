package com.aria.launcher.aria.llm

import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
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

    // --- Phase 4: tool budget / routing -----------------------------------

    @Test
    fun `every tool belongs to exactly one group`() {
        val allToolNames = AriaPrompts.buildTools(
            capabilities = fullCapabilities(),
            skillNames = listOf("weather_skill"),
            notificationContentEnabled = true,
        ).map { it.name }.toSet()

        val grouped = AriaPrompts.toolGroups.values.flatten()

        // No tool is orphaned (unreachable by the router)...
        assertThat(grouped.toSet()).containsAtLeastElementsIn(allToolNames)
        // ...and no tool is double-counted across groups.
        assertThat(grouped).containsNoDuplicates()
    }

    @Test
    fun `parseToolGroups extracts names from a comma-separated reply`() {
        val groups = AriaPrompts.parseToolGroups("communication, web, calendar")
        assertThat(groups).containsExactly("communication", "web", "calendar")
    }

    @Test
    fun `parseToolGroups ignores prose, casing, and unknown names`() {
        val groups = AriaPrompts.parseToolGroups(
            "The user wants to text someone, so: Communication and maybe WEB. (not banking)",
        )
        assertThat(groups).containsExactly("communication", "web")
    }

    @Test
    fun `parseToolGroups returns empty set when nothing matches`() {
        assertThat(AriaPrompts.parseToolGroups("none of these apply")).isEmpty()
        assertThat(AriaPrompts.parseToolGroups("")).isEmpty()
    }

    @Test
    fun `filterToolsByGroups keeps only tools in the selected groups`() {
        val tools = AriaPrompts.buildTools(fullCapabilities())
        val filtered = AriaPrompts.filterToolsByGroups(tools, setOf("web"))
        assertThat(filtered.map { it.name }).containsExactly("fetch_url", "search_web")
    }

    @Test
    fun `toolsForRouterReply falls back to all tools when the router fails`() {
        val tools = AriaPrompts.buildTools(fullCapabilities())
        // null reply models a router error or non-text answer.
        assertThat(AriaPrompts.toolsForRouterReply(null, tools)).isEqualTo(tools)
    }

    @Test
    fun `toolsForRouterReply always includes the floor groups`() {
        val tools = AriaPrompts.buildTools(fullCapabilities())
        val selected = AriaPrompts.toolsForRouterReply("web", tools).map { it.name }
        // Routed group...
        assertThat(selected).containsAtLeast("fetch_url", "search_web")
        // ...plus the always-on floor (apps + utility).
        assertThat(selected).containsAtLeast("open_app", "list_apps", "set_reminder", "get_weather")
    }

    @Test
    fun `toolsForRouterReply falls back to all tools when the reply names no known group`() {
        val tools = AriaPrompts.buildTools(fullCapabilities())
        // A small model that ignores "reply with ONLY group names" must not strand the
        // user on the floor set — an unrecognizable reply falls back to everything.
        assertThat(AriaPrompts.toolsForRouterReply("Sure, I can help with that!", tools))
            .isEqualTo(tools)
        assertThat(AriaPrompts.toolsForRouterReply("", tools)).isEqualTo(tools)
    }

    @Test
    fun `toolsForRouterReply falls back to all tools when the filtered set is empty`() {
        // Only web tools available, but the router picks an unrelated group with no floor
        // overlap → filtered set is empty → fall back to everything offered.
        val webOnly = AriaPrompts.buildTools(emptyList())
            .filter { it.name in setOf("fetch_url", "search_web") }
        assertThat(AriaPrompts.toolsForRouterReply("memory", webOnly)).isEqualTo(webOnly)
    }

    @Test
    fun `editorial agentic tools are capped to heartbeat groups`() {
        val tools = AriaPrompts.buildEditorialTools(
            skillNames = listOf("weather_skill"),
            notificationContentEnabled = true,
            agenticMode = true,
            capabilities = fullCapabilities(),
        ).map { it.name }

        // Heartbeat-relevant groups are offered...
        assertThat(tools).containsAtLeast("fetch_url", "activate_skill", "read_notifications")
        // ...but calendar (create_event) is outside editorialGroups and dropped, even
        // though the editorial policy would otherwise allow it.
        assertThat(tools).doesNotContain("create_event")
        // NEVER-tier launchers stay excluded by the policy filter.
        assertThat(tools).doesNotContain("open_app")
    }

    private fun fullCapabilities(): List<DeviceCapabilityCatalog.AppCapability> =
        listOf("phone", "email", "timer", "calendar", "camera", "share", "music").map { category ->
            DeviceCapabilityCatalog.AppCapability(
                packageName = "com.example.$category",
                appLabel = category,
                capability = category,
                intentTemplate = "",
                category = category,
            )
        }
}
