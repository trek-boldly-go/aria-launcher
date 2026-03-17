# Session 7: AriaContext + BriefItem Foundation + MCP Seams

---

## ARIA Vision (read every session)

ARIA (Adaptive Reasoning Interface for Android) is an AI-native Android launcher. Instead of predicting apps, it predicts **information** — surfacing it proactively as cards. The home screen is a context-aware feed of actionable insight cards, powered by LLM reasoning over device context. ARIA is an **Ambient Agent OS**: each app can be queried, summarized, and acted upon without opening it.

**Core insight:** Google's agentic features are buried in Gemini. ARIA starts at the launcher level — it *is* the home screen.

### Temporal UI, not Spatial UI

| Spatial (do not do this) | Temporal (do this) |
|---|---|
| Fixed app grid | Predicted app row that reorders by context |
| Static widgets | Context-driven cards that appear and disappear |
| User hunts for the right app | ARIA surfaces it before the user thinks to look |
| Notification shade repackaged as cards | ARIA synthesizes alerts into judgments |
| Same layout at 7am and 10pm | Completely different surface at 7am vs 10pm |

**Test for every UI decision:** "Does showing this require the user to do something, or is ARIA already handling it?" If ARIA is handling it, the card should say so with *judgment*, not just data.

### The Brief

The home screen's primary content area is **the Brief** — a scrollable list of BriefItem cards, generated fresh on each context change (not every unlock).

- **Max 5 items.** Ruthless curation is the feature.
- **Judgment, not raw data.** ARIA synthesizes before showing.
- **Items appear and disappear.** Nothing is permanent.
- **Not a notification shade.** Only what ARIA has decided is worth attention.
- **Empty is correct.** Show nothing if nothing is worth showing.

**BriefItem types (1-line summary):**
- `AlertAssessed` — ARIA's verdict on an alert, not the raw alert text
- `ReminderNudge` — time-sensitive nudge for something to act on
- `CalendarEvent` — upcoming event with join/navigate/open action
- `MediaResume` — resumable media the user was consuming
- `LiveDataCard` — live data from MCP source (Phase 8+ seam)
- `ProactiveSuggestion` — pattern-detected suggestion with rationale
- `VenueCard` — venue-aware card shown near a known location
- `ContextBar` — compact weather/location line in greeting area (NOT in brief list)

### Home Screen Layout

```
┌─────────────────────────────┐
│  Good evening, Donovon      │  ← Greeting
│  Sunday · Lisle · 34°F Snow │  ← ContextBar (always present)
├─────────────────────────────┤
│  ○  Ask ARIA anything...    │  ← Chat bar
├─────────────────────────────┤
│  [BriefItem]                │  ← The Brief (max 5, scrollable)
│  [BriefItem]                │
├─────────────────────────────┤
│  [App] [App] [App] [App]    │  ← Predicted apps row
├─────────────────────────────┤
│  [Phone] [Messages] [Maps]  │  ← Dock
└─────────────────────────────┘
```

No app grid. No Google search bar. No static widgets. No folder grid.

### Anti-Patterns to Avoid

- **Notification shade problem**: showing raw notification content as cards — every card must be synthesis
- **Two input bars**: ARIA chat bar + Google search bar visible at once
- **Cards that never leave**: every BriefItem with a natural end must have expiry
- **Animating on every unlock**: animate on context change only, instant load otherwise
- **Excessive text**: 6-word headlines, 12-word subtext — enforce on parsing side
- **Explanatory empty states**: if the Brief is empty, show nothing

---

## Session 7 Deliverables

**Goal**: Architectural pivot. Define the rich context object, Brief data model, and all four MCP seams. No visible UI change — every subsequent session depends on this.

**Why AriaContext**: Current `ContextKey` is a thin tuple (DayType, TimeBucket, LocationHint). The Brief and editorial engine need a full snapshot: calendar events, recent apps, venue info, weather, fired rules.

**New files** (`lawnchair/src/com/aria/launcher/aria/`):
- `engine/AriaContext.kt` — Rich context snapshot with `AriaContext.build()` pulling from ContextSignalManager, CalendarEventProvider, UsageDataRepository
- `engine/AriaContextMonitor.kt` — `Flow<AriaContext>` on meaningful context changes, `distinctUntilChanged()` by bucket hash
- `ui/brief/BriefItem.kt` — Sealed class: AlertAssessed, ReminderNudge, CalendarEvent, MediaResume, LiveDataCard, ProactiveSuggestion, VenueCard, ContextBar + BriefAction + AlertSeverity
- `ui/brief/BriefDataSource.kt` — Interface (MCP Seam 2): sourceId, fetchItems(context), isAvailable(context)
- `ui/brief/BriefAggregator.kt` — Collects from all sources, caps at 5 items. LLM editorial stubbed (heuristic sort for now)
- `engine/ActionExecutor.kt` — Interface + ActionCapability enum + ActionResult sealed class (MCP Seam 1)
- `engine/ActionDispatcher.kt` — Priority-ordered executor dispatch
- `engine/IntentExecutor.kt` — ActionExecutor via Android Intents
- `engine/AriaSkill.kt` — Interface with requiredServers/requiredPermissions (MCP Seam 4)
- `engine/rules/RuleAction.kt` — Sealed class including FetchData variant (MCP Seam 3, no-op until MCP)
- `data/McpModels.kt` — Entity stubs (McpServer, McpCapability, McpExecution) — defined but NOT added to DB yet

**Modified**: `engine/ContextKey.kt` (compatibility with AriaContext)

---

## Spec: BriefItem Type Vocabulary

Every card is one of these types. New types are added deliberately, not on demand.

```kotlin
sealed class BriefItem {

    // An alert that ARIA has already assessed — includes ARIA's verdict
    data class AlertAssessed(
        val icon: String,
        val headline: String,           // ARIA's verdict, not the raw alert text
        val subtext: String?,
        val severity: AlertSeverity,    // INFO, WARNING, CRITICAL
        val action: BriefAction?
    ) : BriefItem()

    // A nudge for something time-sensitive the user might want to act on
    data class ReminderNudge(
        val icon: String,
        val headline: String,
        val subtext: String?,
        val action: BriefAction?
    ) : BriefItem()

    // A calendar event coming up soon
    data class CalendarEvent(
        val title: String,
        val timeDescription: String,    // "in 12 minutes", "at 3:00 PM"
        val location: String?,
        val primaryAction: BriefAction, // "Join" / "Navigate" / "Open"
        val secondaryAction: BriefAction?
    ) : BriefItem()

    // A media item the user was consuming and can resume
    data class MediaResume(
        val title: String,
        val subtitle: String,           // artist, show name, podcast name
        val thumbnailUri: String?,
        val resumeAction: BriefAction
    ) : BriefItem()

    // Live data from an MCP source (Phase 8+ — define now as a seam)
    data class LiveDataCard(
        val sourceId: String,
        val icon: String,
        val headline: String,
        val subtext: String?,
        val action: BriefAction?,
        val refreshedAt: Long
    ) : BriefItem()

    // A proactive suggestion ARIA is making based on pattern detection
    data class ProactiveSuggestion(
        val headline: String,
        val rationale: String,          // brief explanation of why ARIA is suggesting this
        val action: BriefAction,
        val dismissible: Boolean = true
    ) : BriefItem()

    // Venue-aware card shown when near a known location
    data class VenueCard(
        val venueName: String,
        val venueCategory: VenueCategory,
        val headline: String,
        val actions: List<BriefAction>  // max 2 actions
    ) : BriefItem()

    // Compact weather + context line — always present, minimal footprint
    // Rendered in the greeting area, NOT in the scrollable brief list
    data class ContextBar(
        val weatherLine: String,        // "34°F · Snow · Feels like 25°"
        val locationHint: String?,      // "Home", "Work", null if unknown
        val alertCount: Int = 0         // if >0, tap expands to alert details
    ) : BriefItem()
}

data class BriefAction(
    val label: String,                  // button text, max 3 words
    val intentUri: String?,
    val mcpToolCall: McpToolCall? = null // Phase 8: populated by MCP layer
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }
```

---

## Spec: Structured Output (LLM → UI)

The LLM produces a JSON payload. Android makes all rendering decisions. The LLM chooses from a fixed type vocabulary — it cannot invent new types at runtime.

```json
{
  "brief": [
    {
      "type": "alert_assessed",
      "icon": "shield_check",
      "headline": "Flood advisory — you're clear",
      "subtext": "Active until 2am, your area isn't affected",
      "action": null
    },
    {
      "type": "reminder_nudge",
      "icon": "cake",
      "headline": "Ava's birthday in 6 days",
      "subtext": null,
      "action": { "label": "Order something", "intentUri": "https://amazon.com/s?k=birthday+gift" }
    }
  ]
}
```

---

## Spec: Four MCP Seams

> **DO NOT implement MCP in Sessions 7-14.** Define these interfaces now so future sessions don't accidentally close off the architecture. MCP implementation is Phase 8+.

### Seam 1: ActionExecutor interface

Currently actions execute via Intents. MCP will be a second pathway. Define a shared interface so both are interchangeable:

```kotlin
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
        // Intent is always the fallback
        return capable.first().execute(action)
    }
}
```

When MCP arrives in Phase 8, add `McpExecutor : ActionExecutor` at higher priority than `IntentExecutor`.

### Seam 2: BriefDataSource interface

MCP will add live data from external services as additional card sources:

```kotlin
interface BriefDataSource {
    val sourceId: String
    val requiresNetwork: Boolean
    val networkScope: NetworkScope
    suspend fun fetchItems(context: AriaContext): List<BriefItem>
    fun isAvailable(context: AriaContext): Boolean
}

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

### Seam 3: RuleAction.FetchData

Include `FetchData` in the `RuleAction` sealed class (Session 7). It does nothing yet — no executor handles it until Phase 8. The rule compiler's system prompt includes this action type so users can write rules that compile correctly. The executor logs "no handler for FetchData" until Phase 8.

```kotlin
data class FetchData(
    val serverId: String,
    val toolName: String,
    val params: Map<String, String>,
    val onSuccess: RuleAction,      // action to take with the result
    val onFailure: RuleAction?      // optional fallback
) : RuleAction()
```

### Seam 4: AriaSkill interface

Skills are composable automation units — triggered from chat, a rule, or proactively:

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
```

---

## Spec: McpModels Stubs (define, do NOT add to DB)

```kotlin
// Defined in data/McpModels.kt — entity annotations present but NOT added to AriaDatabase yet

@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey val id: String,
    val serverName: String,
    val endpoint: String,
    val transportType: McpTransport,
    val isReachable: Boolean,
    val lastChecked: Long,
    val lastSuccessfulCall: Long?,
    val requiresAuth: Boolean,
    val authToken: String?,
    val networkScope: NetworkScope
)

@Entity(tableName = "mcp_capabilities")
data class McpCapability(
    @PrimaryKey val id: String,
    val serverId: String,
    val toolName: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String?,
    val cachedAt: Long,
    val isAvailable: Boolean
)

@Entity(tableName = "mcp_executions")
data class McpExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val toolName: String,
    val inputJson: String,
    val outputJson: String?,
    val executedAt: Long,
    val durationMs: Long,
    val triggeredBy: ExecutionTrigger,
    val success: Boolean,
    val errorMessage: String?
)

enum class McpTransport { HTTP_SSE, STDIO }
enum class NetworkScope { HOME_WIFI, ANY, VPN_ONLY }
enum class ExecutionTrigger { CHAT, RULE, SKILL, PROACTIVE }
```
