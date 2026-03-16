# ARIA MCP Layer
## Supplement to ARIA_PLAN.md and ARIA_Intelligence_layer.md

> **Implementation Priority: PHASE 8+ — Architecture Only For Now**
>
> This document describes the MCP integration layer for ARIA. Do NOT implement
> any of this during the current build phase. The purpose of this document is to
> ensure that decisions made in Phases 1–7 do not accidentally close off the
> architecture needed to add MCP support cleanly later.
>
> When you see a "Future Seam" callout in this document, that is an instruction
> to leave a specific extension point in the current code — an interface, an
> abstraction boundary, or a placeholder — that will allow MCP to slot in later
> without requiring a rewrite.
>
> Read this document before implementing Phase 5 (Chat) and Phase 6 (Agent).

---

## What MCP Unlocks

Today, ARIA's agent layer works via Android Intents and deep links. Intents get
the user *to* an app and sometimes pre-populate a screen. That's navigation
assistance. MCP is categorically different: it gives ARIA the ability to *take
actions inside apps* and *fetch live data from services* without the user
opening anything.

Concrete examples of what changes:

| Without MCP | With MCP |
|---|---|
| "Open Spotify" (launches app) | "Add this to your workout playlist" (done silently) |
| "Open Home Assistant" (launches app) | "Your back door is unlocked" (card, tap to lock) |
| "Open Calendar" (launches app) | Morning brief card built from actual event data |
| "Open Gmail" (launches app) | "You have 3 emails from Ava" (summarized on card) |
| "Here are directions" (opens Maps) | ETA to next calendar event shown on home screen |

The difference is ARIA becoming an *actor* rather than a *navigator*. Intents
will still exist for cases where the user wants to go into an app. MCP handles
cases where the user just wants the outcome.

---

## Mental Model: ARIA as Universal MCP Client

ARIA does not host MCP servers. It is a client that connects to servers wherever
they live:

```
┌─────────────────────────────────────────────────────┐
│                    ARIA (MCP Client)                │
└──────┬──────────────┬───────────────┬───────────────┘
       │              │               │
       ▼              ▼               ▼
 Home Server     Cloud / API      On-Device
 (home WiFi)     (always on)      (local process)

 - Home Assistant   - Spotify API    - Contacts
 - Ollama           - Gmail          - Calendar
 - Plex             - GitHub         - SMS
 - Proxmox          - Notion         - Files
 - Custom scripts   - Linear
```

When on home WiFi, ARIA has access to the full home server capability set.
When away, it falls back to cloud endpoints and on-device capabilities only.
This graceful degradation is a first-class requirement, not an afterthought.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                   MCP Client Layer                  │
│                                                     │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────┐  │
│  │  Registry   │  │  Discovery   │  │  Executor │  │
│  │  (static)   │  │  (runtime)   │  │  (calls)  │  │
│  └──────┬──────┘  └──────┬───────┘  └─────┬─────┘  │
│         │                │                │        │
│  ┌──────▼────────────────▼────────────────▼─────┐  │
│  │           Capability Cache (Room)            │  │
│  └──────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
         ↑                              ↑
   LLM decides               AriaRuleEngine triggers
   which tool to call        tool calls from rules
```

Four components, clean separation:

- **Registry**: Knows which MCP servers *might* exist for installed apps. Ships
  with ARIA, updated via community contributions. Pure static data.
- **Discovery**: Checks at runtime which servers are actually reachable. Runs
  on network change events, not on every unlock. Results cached in Room.
- **Capability Cache**: Stores the tool manifests (list of available tools and
  their schemas) for each reachable server. Prevents querying servers on every
  request.
- **Executor**: Takes a tool name + parameters, calls the right server, returns
  the result. Called by both the LLM chat layer and the rule engine.

---

## Data Models

Define these now. Do not implement them yet. Having these defined means the
Room schema migration from Phase 1 will not be a breaking change.

```kotlin
// The registry entry — ships with ARIA for known app/server pairings
data class McpRegistryEntry(
    val appPackageName: String,         // e.g. "com.spotify.music"
    val serverName: String,             // e.g. "spotify"
    val defaultEndpoint: String?,       // null if home-server-only
    val isHomeServerOnly: Boolean,      // true = only available on local network
    val discoveryHint: String?,         // mDNS service name or well-known port
    val requiresApiKey: Boolean,
    val officialServer: Boolean,        // false = community-maintained
    val clawHubId: String?              // future: ClawHub registry ID
)

// A server instance that has been discovered and is currently reachable
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey val id: String,         // "${serverName}@${endpoint}"
    val serverName: String,
    val endpoint: String,               // full URL incl. port
    val transportType: McpTransport,    // HTTP_SSE or STDIO (future)
    val isReachable: Boolean,
    val lastChecked: Long,
    val lastSuccessfulCall: Long?,
    val requiresAuth: Boolean,
    val authToken: String?,             // stored in EncryptedSharedPreferences
    val networkScope: NetworkScope      // HOME_WIFI, ANY, VPN_ONLY
)

// Cached tool manifest for a server — avoids re-fetching on every request
@Entity(tableName = "mcp_capabilities")
data class McpCapability(
    @PrimaryKey val id: String,         // "${serverId}::${toolName}"
    val serverId: String,
    val toolName: String,
    val description: String,
    val inputSchemaJson: String,        // JSON Schema for the tool's input
    val outputSchemaJson: String?,
    val cachedAt: Long,
    val isAvailable: Boolean
)

// Execution log — used for debugging, rule trigger counts, and LLM context
@Entity(tableName = "mcp_executions")
data class McpExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val toolName: String,
    val inputJson: String,
    val outputJson: String?,
    val executedAt: Long,
    val durationMs: Long,
    val triggeredBy: ExecutionTrigger,  // CHAT, RULE, SKILL, PROACTIVE
    val success: Boolean,
    val errorMessage: String?
)

enum class McpTransport { HTTP_SSE, STDIO }
enum class NetworkScope { HOME_WIFI, ANY, VPN_ONLY }
enum class ExecutionTrigger { CHAT, RULE, SKILL, PROACTIVE }
```

---

## Future Seams — What to Build Now

These are the specific places in the current Phase 1–7 implementation where
you must leave extension points. Each is a small addition that costs almost
nothing now but prevents a painful rewrite later.

---

### Seam 1: ActionExecutor interface (needed before Phase 6)

Currently, Phase 6 plans to execute actions via Android's AccessibilityService.
MCP will be a second execution pathway for the same actions. Define a shared
interface now so both paths are interchangeable:

```kotlin
// Create this interface during Phase 6. Both AccessibilityExecutor and
// McpExecutor will implement it.
interface ActionExecutor {
    val name: String
    val capabilities: Set<ActionCapability>
    suspend fun execute(action: RuleAction): ActionResult
    suspend fun canExecute(action: RuleAction): Boolean
}

enum class ActionCapability {
    OPEN_APP, SEND_MESSAGE, READ_SCREEN, CLICK_ELEMENT,
    FETCH_DATA, TRIGGER_AUTOMATION, CONTROL_MEDIA,
    MODIFY_CALENDAR, READ_CALENDAR
}

sealed class ActionResult {
    data class Success(val data: Any? = null) : ActionResult()
    data class RequiresConfirmation(val description: String) : ActionResult()
    data class Failure(val reason: String, val recoverable: Boolean) : ActionResult()
}

// The dispatcher — tries executors in priority order
class ActionDispatcher(private val executors: List<ActionExecutor>) {
    suspend fun dispatch(action: RuleAction): ActionResult {
        val capable = executors.filter { it.canExecute(action) }
        if (capable.isEmpty()) return ActionResult.Failure("No executor available", false)

        // MCP preferred over Accessibility when both can handle the action
        // Accessibility preferred over Intent for richer interaction
        // Intent is always the fallback
        return capable.first().execute(action)
    }
}
```

When you implement Phase 6, create `AccessibilityExecutor : ActionExecutor` and
`IntentExecutor : ActionExecutor`. When MCP arrives in Phase 8, add
`McpExecutor : ActionExecutor` and register it in the dispatcher at higher
priority than Accessibility for actions it can handle.

---

### Seam 2: DataSource interface in BriefItem generation (needed before Phase 3)

Currently, home screen cards will be populated by the prediction engine and
calendar data. MCP will add live data from external services (Spotify now
playing, Home Assistant sensor states, Gmail unread count, etc.) as additional
card data sources. Define the interface now:

```kotlin
// Create this interface during Phase 3. AriaCalendarSource,
// AriaPredictionSource will implement it immediately.
// McpDataSource will implement it in Phase 8.
interface BriefDataSource {
    val sourceId: String
    val requiresNetwork: Boolean
    val networkScope: NetworkScope
    suspend fun fetchItems(context: AriaContext): List<BriefItem>
    fun isAvailable(context: AriaContext): Boolean
}

// The aggregator that the home screen ViewModel calls
class BriefAggregator(private val sources: List<BriefDataSource>) {
    suspend fun buildBrief(context: AriaContext): List<BriefItem> {
        val available = sources.filter { it.isAvailable(context) }
        val allItems = available.flatMap { source ->
            try { source.fetchItems(context) }
            catch (e: Exception) { emptyList() }  // never let one source crash the brief
        }
        // LLM editorial pass: rank and trim to 5 items max
        return editorialRank(allItems, context)
    }
}
```

---

### Seam 3: RuleAction.FetchData (needed before Phase 4)

The current `RuleAction` sealed class handles navigation and UI actions.
Add one more variant now that will be the MCP execution entry point:

```kotlin
// Add this to the RuleAction sealed class in ARIA_intelligence_layer.md
// It does nothing yet — no executor handles it until Phase 8
data class FetchData(
    val serverId: String,
    val toolName: String,
    val params: Map<String, String>,
    val onSuccess: RuleAction,      // action to take with the result
    val onFailure: RuleAction?      // optional fallback
) : RuleAction()
```

The rule compiler's system prompt should be updated to include this action type
so users can write rules like "when I get home, fetch the Home Assistant door
sensor and show a card if it's unlocked." The rule will compile correctly. The
executor will log "no handler for FetchData" until Phase 8 without crashing.

---

### Seam 4: AriaSkill interface (can wait until Phase 7, before Phase 8)

Skills are the composable automation unit — a named sequence of data fetches
and actions that can be triggered from the chat, a rule, or proactively.
Define the interface during settings/onboarding work so the architecture is
established:

```kotlin
interface AriaSkill {
    val id: String
    val name: String
    val description: String
    val requiredServers: List<String>       // server IDs this skill needs
    val requiredPermissions: List<String>   // Android permissions needed
    suspend fun execute(
        context: AriaContext,
        params: Map<String, String>,
        executor: ActionDispatcher
    ): SkillResult
}

data class SkillResult(
    val briefItems: List<BriefItem>,        // cards to show on home screen
    val actions: List<RuleAction>,          // actions taken
    val summary: String                     // human-readable summary for chat
)

// Skills registry — ships empty, populated from ClawHub or local definitions
class AriaSkillRegistry {
    private val skills = mutableMapOf<String, AriaSkill>()
    fun register(skill: AriaSkill) { skills[skill.id] = skill }
    fun get(id: String): AriaSkill? = skills[id]
    fun getAvailable(context: AriaContext): List<AriaSkill> =
        skills.values.filter { skill ->
            skill.requiredServers.all { /* check capability cache */ true }
        }
}
```

---

## Phase 8 Implementation Sketch (future reference)

When the time comes, Phase 8 implementation order will be:

**8.1 — MCP Client foundation**
- Implement MCP HTTP+SSE transport in `McpTransportClient`
- Standard MCP handshake: `initialize` → `tools/list` → capability cache write
- Basic auth header support (Bearer token)

```kotlin
class McpTransportClient(private val okHttpClient: OkHttpClient) {
    suspend fun initialize(endpoint: String, authToken: String?): McpSession
    suspend fun listTools(session: McpSession): List<McpToolManifest>
    suspend fun callTool(
        session: McpSession,
        toolName: String,
        arguments: JsonObject
    ): JsonObject
}
```

**8.2 — Discovery service**
- On `NETWORK_STATE_CHANGED` broadcast: scan for reachable servers
- mDNS lookup for home server discovery (no manual IP entry needed)
- Update `mcp_servers` table with reachability status
- Refresh capability cache for newly reachable servers

```kotlin
class McpDiscoveryService(
    private val registry: List<McpRegistryEntry>,
    private val transport: McpTransportClient,
    private val dao: McpServerDao
) {
    // Called on network change
    suspend fun scanAndUpdate(networkContext: NetworkContext)

    // mDNS discovery for home server
    private suspend fun discoverHomeServer(): String?  // returns endpoint URL
}
```

**8.3 — McpExecutor**
- Implements `ActionExecutor` (the seam from Phase 6)
- LLM decides which tool to call from the capability cache
- Handles `RuleAction.FetchData` (the seam from Phase 4)

```kotlin
class McpExecutor(
    private val capabilityCache: McpCapabilityDao,
    private val transport: McpTransportClient,
    private val llmProvider: LlmProvider
) : ActionExecutor {

    override suspend fun execute(action: RuleAction): ActionResult {
        // For FetchData actions: direct tool call
        // For other RuleActions: ask LLM which tool best handles this
        //   given the available capability manifest
    }
}
```

**8.4 — Home Assistant integration (first real skill)**

Home Assistant already has an official MCP server. This will be the first
end-to-end test of the full stack:
- Discovery finds HA server on home WiFi
- Capability cache lists HA tools (get_state, call_service, etc.)
- Rule: "When I get home, fetch door sensor states and show a card for any
  that are open/unlocked"
- McpExecutor calls `homeassistant.get_state` with entity IDs
- BriefItem card rendered with lock/unlock action button

**8.5 — ClawHub / community registry integration**

- Define ClawHub registry format (simple JSON, hosted on GitHub)
- ARIA polls for registry updates weekly
- Users can submit new server entries via PR (community model)
- In-app browser for discovering available skills

---

## Security Model

MCP gives ARIA significant power. The security model must be explicit:

```
Trust Levels (lowest to highest):
  NONE → DISCOVERED → CONFIGURED → VERIFIED → TRUSTED

- NONE: Server in registry but not yet seen
- DISCOVERED: Server responded to initialize handshake
- CONFIGURED: User has reviewed and enabled this server  
- VERIFIED: Server has a known certificate / ClawHub-verified
- TRUSTED: User has explicitly granted elevated permissions

Rules:
- No tool calls until server reaches CONFIGURED or higher
- Destructive tools (send, delete, purchase) require TRUSTED
- All tool calls logged to McpExecution table
- User can review full execution log in Settings → ARIA Log
- Servers can be revoked instantly — all cached credentials cleared
```

Auth tokens are stored in Android's `EncryptedSharedPreferences`, never in
Room directly. The `McpServer.authToken` field in the Room entity stores only
a key reference, not the token itself.

---

## What This Enables Long-Term

Once this layer is built, the capabilities that become possible without any
additional core ARIA development:

- **Home screen as dashboard**: Hue lights state, door sensors, Plex now
  playing, server health — all as live BriefItems from HA and Proxmox MCP
  servers, surfaced only when relevant
- **Truly silent automation**: "When I leave work WiFi, turn off my office
  desk lamp" — MCP rule that calls HA directly, no app opened, no card shown
- **Cross-app workflows**: "After my last meeting ends, send Ava a message
  saying I'm leaving soon and start navigation home" — calendar MCP reads
  the event, messaging MCP drafts the text, Maps intent handles navigation
- **Community skills**: A "Morning Briefing" skill on something like ClawHub that chains
  calendar + weather + news + HA sensors into a single rich card, authored
  by the community, installed in one tap
