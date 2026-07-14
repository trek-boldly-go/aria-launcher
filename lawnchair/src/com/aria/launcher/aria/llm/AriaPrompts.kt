package com.aria.launcher.aria.llm

import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import com.aria.launcher.aria.engine.EditorialToolPolicy
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
            You are ARIA, the user's personal assistant living inside their Android home screen.
            You speak like a knowledgeable friend — warm, direct, and concise. You have opinions
            and you're not afraid to make a recommendation. You're always one step ahead.

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
            - Talk like a person, not a system. "I pulled up directions" not "Directions have been retrieved."
            - Keep responses concise: 1-3 sentences unless the user asks for more.
            - When taking actions, use the provided tools rather than describing what to do.
            - Be aware of time of day and user context when making suggestions.
            - After using a tool, briefly confirm what you did in a natural way.
            - Use what you know about this user to personalize your responses, but don't mention it unprompted.
            - You can be playful when the moment fits, but never waste the user's time.
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
        ToolDefinition(
            name = "lookup_contact",
            description = "Search device contacts by name; returns phone numbers and emails. " +
                "Call before compose_message/make_call/send_email when the user names a person. " +
                "Errors if contact access is off — do NOT retry.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Name or partial name to search for")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))),
            ),
        ),
        ToolDefinition(
            name = "get_calendar_events",
            description = "Read upcoming calendar events for \"what's on my calendar\", \"am I " +
                "free\", or \"when is my next X\". Set days_ahead to cover the question " +
                "(1=today, 7=this week, up to 90); filter the results yourself.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("days_ahead") {
                        put("type", "integer")
                        put(
                            "description",
                            "How many days into the future to look. 1–90. Defaults to 7.",
                        )
                    }
                    putJsonObject("max_results") {
                        put("type", "integer")
                        put("description", "Maximum events to return. 1–100. Defaults to 30.")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(emptyList()),
            ),
        ),
        ToolDefinition(
            name = "get_current_location",
            description = "Returns the user's current GPS location as latitude/longitude with a " +
                "human-readable label (city or locality) when available. Cached for 60 seconds " +
                "to avoid GPS spam.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {},
                "required" to kotlinx.serialization.json.JsonArray(emptyList()),
            ),
        ),
        ToolDefinition(
            name = "list_apps",
            description = "List installed apps with launcher icons; optional query filters by " +
                "label or package. Use when the user names an app by description (\"my budget " +
                "app\") and you don't know its package. Returns label — package_name pairs.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put(
                            "description",
                            "Optional: substring to filter apps by label or package name",
                        )
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(emptyList()),
            ),
        ),
        ToolDefinition(
            name = "get_weather",
            description = "Get the current weather snapshot for the user's location " +
                "(temperature, conditions, wind). Use for any direct weather question.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {},
                "required" to kotlinx.serialization.json.JsonArray(emptyList()),
            ),
        ),
        ToolDefinition(
            name = "remember",
            description = "Store a long-term fact about the user so future conversations can " +
                "reference it. Use ONLY for durable, non-trivial facts the user has explicitly " +
                "shared (preferences, allergies, important people/places, routines). Do NOT " +
                "call this for ephemeral context. Categories: preference, routine, person, " +
                "place, general.",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("fact") {
                        put("type", "string")
                        put("description", "The fact to remember (5–300 characters)")
                    }
                    putJsonObject("category") {
                        put("type", "string")
                        put(
                            "description",
                            "One of: preference, routine, person, place, general. Defaults to general.",
                        )
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("fact"))),
            ),
        ),
        ToolDefinition(
            name = "forget",
            description = "Find a stored memory matching the query and ask the user to confirm " +
                "deletion. The user must reply \"yes\" before the memory is actually deleted. " +
                "Use when the user asks ARIA to forget something (\"forget that I like coffee\").",
            inputSchema = mapOf(
                "properties" to buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Words from the fact you want to delete")
                    }
                },
                "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))),
            ),
        ),
    )

    /** Tool that lets the LLM read notification message bodies on demand. */
    val readNotificationsTool = ToolDefinition(
        name = "read_notifications",
        description = "Read the text content of active notifications. " +
            "Use when notification titles suggest actionable content " +
            "(locations, times, requests) that would benefit from reading the full message.",
        inputSchema = mapOf(
            "properties" to buildJsonObject {
                putJsonObject("package_filter") {
                    put("type", "string")
                    put("description", "Optional package name to filter notifications by app")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("description", "Max notifications to return (default 10)")
                }
            },
            "required" to kotlinx.serialization.json.JsonArray(emptyList()),
        ),
    )

    /**
     * Named tool groups, used to shrink the per-request tool set on small models.
     * A cheap router pre-pass (see [toolRouterSystemPrompt]) picks the groups a user
     * turn might need; [filterToolsByGroups] then keeps only those tools. Every tool
     * belongs to exactly one group so nothing becomes permanently unreachable.
     */
    val toolGroups: Map<String, List<String>> = mapOf(
        "communication" to listOf("lookup_contact", "compose_message", "make_call", "send_email"),
        "calendar" to listOf("get_calendar_events", "create_event"),
        "web" to listOf("fetch_url", "search_web"),
        "apps" to listOf("open_app", "list_apps"),
        "memory" to listOf("remember", "forget"),
        "media" to listOf("play_music"),
        "utility" to listOf(
            "set_reminder",
            "set_timer",
            "get_weather",
            "get_current_location",
            "take_photo",
            "share_text",
            "get_directions",
        ),
        "notifications" to listOf("read_notifications"),
        "skills" to listOf("activate_skill"),
    )

    /** Groups always kept regardless of routing — the capability floor. */
    val floorGroups: Set<String> = setOf("apps", "utility")

    /** Groups relevant to the heartbeat/editorial engine when running agentically. */
    val editorialGroups: Set<String> = setOf("web", "skills", "notifications", "utility", "communication")

    /**
     * System prompt for the tool-router pre-pass: given a user message, the model
     * replies with a comma-separated list of the [toolGroups] that might help. Parsed
     * leniently by [parseToolGroups]; any failure falls back to offering all tools.
     */
    fun toolRouterSystemPrompt(): String = """
        You are a tool router. Read the user's message and decide which tool groups might help.
        Groups:
        - communication: contacts, texting, calling, email
        - calendar: reading or creating calendar events
        - web: fetching a URL or searching the web
        - apps: opening or listing installed apps
        - memory: remembering or forgetting facts about the user
        - media: playing music
        - utility: reminders, timers, weather, location, directions, camera, sharing
        - notifications: reading notification contents
        - skills: activating an installed skill

        Reply with ONLY a comma-separated list of group names that might help.
        If unsure, include more groups rather than fewer. No other text.
    """.trimIndent()

    /**
     * Lenient parser for the router reply: extracts any known group names, ignoring
     * punctuation, casing, and surrounding prose. Returns the recognized groups (may
     * be empty — callers add [floorGroups] and fall back to all tools if nothing useful).
     */
    fun parseToolGroups(response: String): Set<String> {
        val valid = toolGroups.keys
        return response.lowercase()
            .split(Regex("[^a-z_]+"))
            .filter { it in valid }
            .toSet()
    }

    /** Keeps only the tools whose names belong to one of [groups]. */
    fun filterToolsByGroups(
        tools: List<ToolDefinition>,
        groups: Set<String>,
    ): List<ToolDefinition> {
        val allowed = groups.flatMap { toolGroups[it].orEmpty() }.toSet()
        return tools.filter { it.name in allowed }
    }

    /**
     * Resolves the router pre-pass into the tool set to offer. A null [reply] (router
     * failed or gave a non-text answer) or an empty result falls back to [allTools] —
     * the pre-Phase-4 behavior. Otherwise keeps the routed groups plus the [floorGroups].
     */
    fun toolsForRouterReply(
        reply: String?,
        allTools: List<ToolDefinition>,
    ): List<ToolDefinition> {
        if (reply == null) return allTools
        val groups = parseToolGroups(reply) + floorGroups
        val filtered = filterToolsByGroups(allTools, groups)
        return filtered.ifEmpty { allTools }
    }

    /**
     * Builds a dynamic tool list based on what the device can actually do.
     * Starts with [coreTools] and adds capability-backed tools.
     * If skill names are provided, includes the activate_skill tool.
     */
    fun buildTools(
        capabilities: List<DeviceCapabilityCatalog.AppCapability>,
        skillNames: List<String> = emptyList(),
        notificationContentEnabled: Boolean = false,
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

        if (notificationContentEnabled) {
            dynamic.add(readNotificationsTool)
        }

        return coreTools + dynamic
    }

    /**
     * Builds the tool set for the editorial engine (heartbeat).
     *
     * When [agenticMode] is false (default), only includes fetch_url, activate_skill,
     * and read_notifications — the original restricted set.
     *
     * When [agenticMode] is true, includes all tools where
     * [EditorialToolPolicy.isOfferedToEditorial] returns true, filtered by device
     * capabilities. This expands the editorial engine to act on behalf of the user.
     */
    fun buildEditorialTools(
        skillNames: List<String>,
        notificationContentEnabled: Boolean = false,
        agenticMode: Boolean = false,
        capabilities: List<DeviceCapabilityCatalog.AppCapability> = emptyList(),
    ): List<ToolDefinition> {
        if (!agenticMode) {
            val tools = mutableListOf(coreTools.first { it.name == "fetch_url" })
            if (skillNames.isNotEmpty()) {
                tools.add(buildActivateSkillTool(skillNames))
            }
            if (notificationContentEnabled) {
                tools.add(readNotificationsTool)
            }
            return tools
        }

        // Agentic mode: expose the tools that pass the editorial policy filter, then
        // additionally cap to the groups relevant to the heartbeat so small models
        // aren't handed the full agentic surface.
        val allTools = buildTools(capabilities, skillNames, notificationContentEnabled)
        val policyFiltered = allTools.filter { EditorialToolPolicy.isOfferedToEditorial(it.name) }
        return filterToolsByGroups(policyFiltered, editorialGroups)
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
