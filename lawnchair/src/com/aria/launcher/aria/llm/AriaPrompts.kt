package com.aria.launcher.aria.llm

import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object AriaPrompts {

    fun buildSystemPrompt(
        signals: ContextSignalManager,
        contextKey: ContextKey,
        recentApps: List<String> = emptyList(),
        upcomingEvents: List<String> = emptyList(),
        userMemories: List<String> = emptyList(),
        capabilitySummary: String = "",
        activitySummary: String = "",
        skillCatalog: String = "",
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

        val capabilitiesSection = if (capabilitySummary.isNotEmpty()) {
            """
            Available device capabilities:
            $capabilitySummary
            """
        } else {
            ""
        }

        val activitiesSection = if (activitySummary.isNotEmpty()) {
            """
            Available app screens (use component param in open_app for targeted navigation):
            $activitySummary
            When you can open a specific screen instead of the whole app, prefer the specific screen.
            """
        } else {
            ""
        }

        val skillsSection = if (skillCatalog.isNotEmpty()) {
            """
            $skillCatalog
            Use fetch_url to retrieve data from APIs when skills instruct you to.
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

            $capabilitiesSection
            $activitiesSection
            $skillsSection
            Guidelines:
            - Keep responses concise: 1-3 sentences unless the user asks for more.
            - When taking actions, use the provided tools rather than describing what to do.
            - Prioritize actionable information over generic responses.
            - Be aware of time of day and user context when making suggestions.
            - You are in an interactive chat on the user's home screen.
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

    /** The base set of tools always available in chat and editorial engine. */
    val coreTools: List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "fetch_url",
            description = "Fetch data from a URL and return the response body. " +
                "Use for REST APIs, JSON endpoints, and web data. " +
                "Returns the HTTP status code and response text.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "The URL to fetch (must be HTTPS or HTTP)")
                    }
                    putJsonObject("method") {
                        put("type", "string")
                        put("description", "HTTP method: GET or POST. Defaults to GET.")
                    }
                    putJsonObject("headers") {
                        put("type", "object")
                        put("description", "Optional HTTP headers as key-value pairs (e.g., Authorization)")
                    }
                    putJsonObject("body") {
                        put("type", "string")
                        put("description", "Optional request body for POST requests")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))),
            ),
        ),
        ToolDefinition(
            name = "open_app",
            description = "Open an installed app, optionally targeting a specific screen via deep link or component name",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("package_name") {
                        put("type", "string")
                        put("description", "Android package name (e.g., com.spotify.music)")
                    }
                    putJsonObject("intent_uri") {
                        put("type", "string")
                        put(
                            "description",
                            "Deep link URI to open a specific screen (e.g., spotify://search). Optional.",
                        )
                    }
                    putJsonObject("component") {
                        put("type", "string")
                        put(
                            "description",
                            "Fully qualified activity component (e.g., com.spotify.music/.ui.SearchActivity). Optional.",
                        )
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
            description = "Get directions to a destination using maps",
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
            description = "Compose a text message (SMS) to a contact",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("contact") {
                        put("type", "string")
                        put("description", "Contact name or phone number")
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

    /**
     * Builds a dynamic tool list based on what the device can actually do.
     * Starts with [coreTools] and adds capability-backed tools.
     * If skill names are provided, includes the activate_skill tool.
     */
    fun buildTools(
        capabilities: List<DeviceCapabilityCatalog.AppCapability>,
        skillNames: List<String> = emptyList(),
    ): List<ToolDefinition> {
        val capCategories = capabilities.map { it.category }.toSet()
        val dynamic = mutableListOf<ToolDefinition>()

        if ("phone" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "make_call",
                    description = "Make a phone call to a number or contact",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {
                            putJsonObject("number") {
                                put("type", "string")
                                put("description", "Phone number to call")
                            }
                        },
                        "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("number"))),
                    ),
                ),
            )
        }

        if ("email" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "send_email",
                    description = "Compose and send an email",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {
                            putJsonObject("to") {
                                put("type", "string")
                                put("description", "Recipient email address")
                            }
                            putJsonObject("subject") {
                                put("type", "string")
                                put("description", "Email subject line")
                            }
                            putJsonObject("body") {
                                put("type", "string")
                                put("description", "Email body text")
                            }
                        },
                        "required" to kotlinx.serialization.json.JsonArray(
                            listOf(JsonPrimitive("to"), JsonPrimitive("subject"), JsonPrimitive("body")),
                        ),
                    ),
                ),
            )
        }

        if ("timer" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "set_timer",
                    description = "Set a countdown timer",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {
                            putJsonObject("seconds") {
                                put("type", "integer")
                                put("description", "Duration in seconds")
                            }
                            putJsonObject("label") {
                                put("type", "string")
                                put("description", "Timer label (optional)")
                            }
                        },
                        "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("seconds"))),
                    ),
                ),
            )
        }

        if ("calendar" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "create_event",
                    description = "Create a calendar event",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {
                            putJsonObject("title") {
                                put("type", "string")
                                put("description", "Event title")
                            }
                            putJsonObject("start_time") {
                                put("type", "string")
                                put("description", "Start time (ISO 8601 or natural language)")
                            }
                            putJsonObject("end_time") {
                                put("type", "string")
                                put("description", "End time (ISO 8601 or natural language, optional)")
                            }
                        },
                        "required" to kotlinx.serialization.json.JsonArray(
                            listOf(JsonPrimitive("title"), JsonPrimitive("start_time")),
                        ),
                    ),
                ),
            )
        }

        if ("camera" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "take_photo",
                    description = "Open the camera to take a photo",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {},
                        "required" to kotlinx.serialization.json.JsonArray(emptyList()),
                    ),
                ),
            )
        }

        if ("share" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "share_text",
                    description = "Share text with another app",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {
                            putJsonObject("text") {
                                put("type", "string")
                                put("description", "Text to share")
                            }
                        },
                        "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("text"))),
                    ),
                ),
            )
        }

        if ("music" in capCategories) {
            dynamic.add(
                ToolDefinition(
                    name = "play_music",
                    description = "Play music — opens a music app, optionally searching for a song or artist",
                    inputSchema = mapOf(
                        "properties" to buildJsonObject {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Song, artist, or genre to search for (optional)")
                            }
                        },
                        "required" to kotlinx.serialization.json.JsonArray(emptyList()),
                    ),
                ),
            )
        }

        // Add activate_skill tool if skills are available
        if (skillNames.isNotEmpty()) {
            dynamic.add(buildActivateSkillTool(skillNames))
        }

        return coreTools + dynamic
    }

    /**
     * Builds the minimal tool set for the editorial engine (heartbeat).
     * Only includes fetch_url and optionally activate_skill — not the full device tool set.
     */
    fun buildEditorialTools(skillNames: List<String>): List<ToolDefinition> {
        val tools = mutableListOf(coreTools.first { it.name == "fetch_url" })
        if (skillNames.isNotEmpty()) {
            tools.add(buildActivateSkillTool(skillNames))
        }
        return tools
    }

    /** Creates the activate_skill tool definition for the given skill names. */
    fun buildActivateSkillTool(skillNames: List<String>): ToolDefinition = ToolDefinition(
        name = "activate_skill",
        description = "Load the full instructions for an installed skill. " +
            "Call this before using a skill to get its detailed instructions. " +
            "Available skills: ${skillNames.joinToString(", ")}",
        inputSchema = mapOf(
            "properties" to buildJsonObject {
                putJsonObject("name") {
                    put("type", "string")
                    put(
                        "description",
                        "Skill name to activate (one of: ${skillNames.joinToString(", ")})",
                    )
                }
            },
            "required" to kotlinx.serialization.json.JsonArray(
                listOf(JsonPrimitive("name")),
            ),
        ),
    )

    /** Backward-compatible alias for code that still references ariaTools. */
    val ariaTools: List<ToolDefinition> get() = coreTools

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
