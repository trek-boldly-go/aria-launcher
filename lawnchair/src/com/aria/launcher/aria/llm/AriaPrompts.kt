package com.aria.launcher.aria.llm

import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.engine.ContextKey
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AriaPrompts {

    fun buildSystemPrompt(
        signals: ContextSignalManager,
        contextKey: ContextKey,
        recentApps: List<String> = emptyList(),
        upcomingEvents: List<String> = emptyList(),
        userMemories: List<String> = emptyList(),
    ): String {
        val now = SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", Locale.getDefault()).format(Date())
        val memoriesSection = if (userMemories.isNotEmpty()) {
            """

            What you know about this user:
            ${userMemories.joinToString("\n") { "- $it" }}
            """
        } else {
            ""
        }
        return """
            You are ARIA, an AI assistant embedded in the user's Android home screen launcher.
            You are proactive, concise, and context-aware. You surface information the user needs
            before they ask for it.

            Current context:
            - Time: $now
            - Context key: ${contextKey.toStringKey()}
            - Charging: ${signals.isCharging.value}
            - WiFi: ${signals.wifiSsid.value ?: "disconnected"}
            - Activity: ${signals.detectedActivity.value?.let { activityName(it) } ?: "unknown"}
            - Recent apps: ${recentApps.take(5).joinToString(", ").ifEmpty { "none" }}
            - Upcoming events: ${upcomingEvents.take(3).joinToString("; ").ifEmpty { "none" }}
            $memoriesSection
            Guidelines:
            - Keep responses concise: 1-3 sentences unless the user asks for more.
            - When taking actions, use the provided tools rather than describing what to do.
            - Prioritize actionable information over generic responses.
            - Be aware of time of day and user context when making suggestions.
            - You are in an interactive chat on the user's home screen.
            - You can open apps, search the web, set reminders, get directions, and compose messages.
            - After using a tool, briefly confirm the action was taken.
            - Use what you know about this user to personalize your responses, but don't mention it unprompted.
        """.trimIndent()
    }

    fun buildMemoryExtractionPrompt(
        recentMessages: List<String>,
        existingMemories: List<String>,
    ): String {
        val existingSection = if (existingMemories.isNotEmpty()) {
            """
            Already known facts (do NOT repeat these):
            ${existingMemories.joinToString("\n") { "- $it" }}
            """
        } else {
            ""
        }
        return """
            You are a memory extraction system. Analyze the conversation below and extract
            any NEW facts about the user that would be useful to remember for future conversations.

            Focus on:
            - Personal preferences (favorite apps, food, music, etc.)
            - Routines and habits (commute times, workout schedule, etc.)
            - Important people (family, friends, coworkers mentioned by name)
            - Places they frequent (home, work, gym, etc.)
            - Any stated preferences about how they want to interact

            Recent conversation:
            ${recentMessages.joinToString("\n")}
            $existingSection
            Rules:
            - Output one fact per line, prefixed with category: [preference], [routine], [person], [place], or [general]
            - Only extract facts explicitly stated or strongly implied by the user
            - Do NOT extract trivial or obvious things (like "user asked about weather")
            - Do NOT repeat facts already known
            - If there are no new facts worth remembering, respond with: NONE
            - Keep each fact concise (under 100 characters)
        """.trimIndent()
    }

    fun buildCardCurationPrompt(
        skillResults: List<String>,
    ): String = """
        You are ranking information cards for a user's home screen.
        Given the following skill results, rank them by relevance and urgency.
        Return a JSON array of skill IDs in priority order, highest priority first.
        Only include cards that are currently relevant.

        Skill results:
        ${skillResults.joinToString("\n")}

        Respond with only the JSON array of skill IDs, no other text.
    """.trimIndent()

    val ariaTools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "open_app",
            description = "Open an installed app by package name",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("package_name") {
                        put("type", "string")
                        put("description", "Android package name (e.g., com.spotify.music)")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("package_name"))),
            ),
        ),
        ToolDefinition(
            name = "search_web",
            description = "Search the web for information",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Search query")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))),
            ),
        ),
        ToolDefinition(
            name = "set_reminder",
            description = "Set a reminder for the user",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "Reminder message")
                    }
                    putJsonObject("time") {
                        put("type", "string")
                        put("description", "When to remind (ISO 8601 or natural language)")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive("message"), JsonPrimitive("time")),
                ),
            ),
        ),
        ToolDefinition(
            name = "get_directions",
            description = "Get directions to a destination",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("destination") {
                        put("type", "string")
                        put("description", "Destination address or place name")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("destination"))),
            ),
        ),
        ToolDefinition(
            name = "compose_message",
            description = "Compose a message to a contact",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("contact") {
                        put("type", "string")
                        put("description", "Contact name or number")
                    }
                    putJsonObject("message") {
                        put("type", "string")
                        put("description", "Message content")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive("contact"), JsonPrimitive("message")),
                ),
            ),
        ),
    )

    private fun activityName(activityType: Int): String = when (activityType) {
        0 -> "in vehicle"
        1 -> "on bicycle"
        2 -> "on foot"
        3 -> "still"
        7 -> "walking"
        8 -> "running"
        else -> "unknown ($activityType)"
    }
}
