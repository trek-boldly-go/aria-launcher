# ARIA — Full Build Plan

## What Is ARIA

ARIA (Adaptive Reasoning Interface for Android) is an AI-native Android launcher forked from Lawnchair 2. Instead of just predicting which app you'll open, ARIA predicts what **information** you need and surfaces it proactively as cards on your home screen. Instead of launching Gmail, see your inbox summary. Instead of opening your calendar, see your next meeting with a join button. The AI is ambient, not opt-in — every unlock is a touchpoint.

ARIA is an **Ambient Agent OS**: a skill-based system where each installed app can be queried, summarized, and acted upon without ever opening it. The home screen becomes a context-aware feed of actionable insight cards, powered by LLM reasoning over device context signals.

**Core insight:** Google's agentic features are buried inside the Gemini app. ARIA starts at the launcher level — it *is* the home screen.

---

## The Core Philosophy: Temporal UI, Not Spatial UI

Traditional launchers are **spatial**. Apps live in fixed grid positions. The user navigates to things. The phone is a map and the user travels through it. This made sense when the phone was dumb and the user was the intelligence.

ARIA is **temporal**. It knows what moment the user is in — the time, the location, the context, the calendar, the recent behavior — and presents what is relevant to *right now*. The user does not navigate. Things arrive. The home screen is not a place the user goes. It is a **surface that changes around the user**.

| Spatial (do not do this) | Temporal (do this) |
|---|---|
| Fixed app grid the user arranges | Predicted app row that reorders by context |
| Static widgets the user places | Context-driven cards that appear and disappear |
| User hunts for the right app | ARIA surfaces it before the user thinks to look |
| Notification shade repackaged as cards | ARIA synthesizes alerts into judgments |
| Search bar as the primary input | Chat bar as the escape hatch for what ARIA missed |
| Same layout at 7am and 10pm | Completely different surface at 7am vs 10pm |

### The test for every UI decision

Before adding any element to the home screen, ask:
**"Does showing this require the user to do something, or is ARIA already handling it?"**

If ARIA is already handling it, the card should say so — not just forward the raw information. A flood advisory card should not say "Flood Advisory — Open Weather." It should say "Flood advisory until 2am — your area isn't affected." The difference is the presence of *judgment*, not just *data*.

### Philosophies

1. **Battery-first** — All ML inference and trend analysis runs during a nightly charging window. Daytime execution only applies already-computed decisions.
2. **Privacy-first** — No usage data ever leaves the device unless the user explicitly configures a remote LLM endpoint.
3. **LLM-agnostic** — All LLM calls go through a provider abstraction layer. Swapping Claude for Gemini for Ollama requires zero changes outside the config.
4. **Launcher-native** — Not a widget or overlay. The actual home screen.
5. **Skills over apps** — Instead of predicting which app to open, predict what information to surface and what actions to take.
6. **Open base** — Fork Lawnchair 2 (open source, Kotlin, Pixel Launcher parity) as the shell.

---

## The Brief: ARIA's Primary Output Surface

The home screen's primary content area is called **the Brief**. It is a scrollable list of BriefItem cards, generated fresh on each context change (not on every unlock — only when the context bucket actually changes).

### Properties of the Brief

- **Maximum 5 items.** If ARIA cannot decide what is important enough to show in 5 items, it is not doing its job. Ruthless curation is the feature.
- **Each item reflects judgment, not raw data.** ARIA has already thought about the item before showing it. It does not forward notifications. It synthesizes.
- **Items appear and disappear.** A meeting card appears 15 minutes before a meeting and is gone 30 minutes after it starts. Nothing is permanent.
- **The Brief is not a notification shade.** Android already has one. ARIA's Brief contains only things ARIA has decided are worth the user's attention, framed in ARIA's voice.
- **Empty is correct.** If the Brief is empty, show nothing — not a card that says "Nothing to show right now."

### BriefItem Type Vocabulary

Every card on the home screen is one of these types. New types are added deliberately, not on demand.

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

### Structured Output: How the LLM Talks to the UI

The LLM never writes layout code. The LLM never produces markdown that gets rendered directly. It produces a **JSON payload** that ARIA's native Compose components know how to render. This solves the wall-of-text problem entirely. The LLM makes editorial decisions (what to show, in what order, how to frame it). Android makes all rendering decisions.

#### The LLM's output format

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
      "action": {
        "label": "Order something",
        "intentUri": "https://amazon.com/s?k=birthday+gift"
      }
    },
    {
      "type": "media_resume",
      "icon": "play_circle",
      "headline": "The Lazarus Project · Ep. 4",
      "subtext": "Picked up where you left off",
      "action": {
        "label": "Resume",
        "intentUri": "spotify://episode/..."
      }
    }
  ]
}
```

The `type` field maps to a specific Composable. The LLM chooses from a fixed vocabulary of types. It cannot invent new types at runtime.

---

## Home Screen Layout

```
┌─────────────────────────────┐
│  Good evening, Donovon      │  ← Greeting, updates by time of day
│  Sunday · Lisle · 34°F Snow │  ← ContextBar (always present, one line)
├─────────────────────────────┤
│  ○  Ask ARIA anything...    │  ← Chat bar — always visible, not dominant
├─────────────────────────────┤
│                             │
│  [BriefItem]                │  ← The Brief — scrollable, max 5 items
│  [BriefItem]                │
│  [BriefItem]                │
│                             │
├─────────────────────────────┤
│  [App] [App] [App] [App]    │  ← Predicted apps row — 4-6, reorders by context
├─────────────────────────────┤
│  [Phone] [Messages] [Maps]  │  ← Dock — 3-4 static or lightly predicted apps
└─────────────────────────────┘
```

### What is NOT in this layout

- **No app grid.** Apps are in the predicted row or the app drawer (swipe up).
- **No Google search bar.** The ARIA chat bar IS the search. Disable the Google search bar by default in Lawnchair settings.
- **No static widgets.** Widgets are replaced by BriefItems which are context-aware and temporary. Traditional widget placement remains available as an opt-in for power users, but it is not the default experience.
- **No folder grid on the home screen.** Folders live in the app drawer.

### Compose Implementation Structure

```kotlin
@Composable
fun AriaHomeScreen(
    viewModel: AriaHomeViewModel,
    onChatBarTap: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)  // wallpaper shows through
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        AriaGreeting(
            greeting = uiState.greeting,
            contextBar = uiState.contextBar
        )

        Spacer(modifier = Modifier.height(16.dp))

        AriaChatBar(
            onTap = onChatBarTap,
            placeholder = "Ask ARIA anything..."
        )

        Spacer(modifier = Modifier.height(16.dp))

        AriaBrief(
            items = uiState.briefItems,
            onActionClick = { action -> viewModel.executeAction(action) },
            onItemDismiss = { item -> viewModel.dismissItem(item) }
        )

        Spacer(modifier = Modifier.weight(1f))

        PredictedAppsRow(
            apps = uiState.predictedApps,
            onAppClick = { pkg -> viewModel.launchApp(pkg) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        AriaDock(
            apps = uiState.dockApps,
            onAppClick = { pkg -> viewModel.launchApp(pkg) }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}
```

### The Brief Composable

```kotlin
@Composable
fun AriaBrief(
    items: List<BriefItem>,
    onActionClick: (BriefAction) -> Unit,
    onItemDismiss: (BriefItem) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = items,
            key = { it.stableKey() }
        ) { item ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                BriefItemCard(
                    item = item,
                    onActionClick = onActionClick,
                    onDismiss = if (item.isDismissible()) {
                        { onItemDismiss(item) }
                    } else null
                )
            }
        }
    }
}

@Composable
fun BriefItemCard(
    item: BriefItem,
    onActionClick: (BriefAction) -> Unit,
    onDismiss: (() -> Unit)?
) {
    when (item) {
        is BriefItem.AlertAssessed -> AlertAssessedCard(item, onActionClick, onDismiss)
        is BriefItem.ReminderNudge -> ReminderNudgeCard(item, onActionClick, onDismiss)
        is BriefItem.CalendarEvent -> CalendarEventCard(item, onActionClick)
        is BriefItem.MediaResume -> MediaResumeCard(item, onActionClick)
        is BriefItem.LiveDataCard -> LiveDataCard(item, onActionClick)
        is BriefItem.ProactiveSuggestion -> ProactiveSuggestionCard(item, onActionClick, onDismiss)
        is BriefItem.VenueCard -> VenueCard(item, onActionClick)
        is BriefItem.ContextBar -> {}  // rendered in greeting area only
    }
}
```

### Card Visual Style

All cards share a common visual language:

```kotlin
@Composable
fun BriefCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
```

- **Rounded corners**: 16dp
- **Semi-transparent surface**: 85% opacity, wallpaper breathes through
- **No borders**: elevation provides separation
- **Action buttons**: text style only, max 2 per card, max 3 words each
- **Icons**: Material Symbols filled, 20dp, muted foreground color
- **Headline**: `MaterialTheme.typography.titleSmall`, single line
- **Subtext**: `MaterialTheme.typography.bodySmall`, max 2 lines, muted color

### Context Change vs. Unlock

**ARIA does not regenerate the Brief on every unlock.**

Regenerating on every unlock would mean an LLM call on every unlock — expensive, slow, and unnecessary. Instead:

- The Brief is regenerated when the **context bucket changes**: a meaningful shift in time, location, or detected activity
- On unlock, ARIA reads the already-computed Brief from cache and renders it instantly (under 16ms, no network call)
- The Brief is also regenerated when:
  - A new calendar event enters the 15-minute window
  - The WiFi network changes
  - The detected activity changes significantly (STILL to IN_VEHICLE)
  - An MCP data source pushes a new item (Phase 8+)
  - The user dismisses an item

```kotlin
class AriaHomeViewModel(
    private val briefAggregator: BriefAggregator,
    private val predictionRepository: PredictionRepository,
    private val contextMonitor: AriaContextMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(AriaUiState.empty())
    val uiState: StateFlow<AriaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            contextMonitor.contextChanges
                .distinctUntilChanged()     // only emit on actual context changes
                .collectLatest { context ->
                    refreshBrief(context)
                }
        }
    }

    private suspend fun refreshBrief(context: AriaContext) {
        val brief = briefAggregator.buildBrief(context)
        val apps = predictionRepository.getPredictedApps(context)
        _uiState.update {
            it.copy(
                briefItems = brief,
                predictedApps = apps,
                greeting = buildGreeting(context),
                contextBar = buildContextBar(context)
            )
        }
    }
}
```

### The Greeting

The greeting is not decorative. It acknowledges the user's current moment. It should feel like a smart assistant who knows what is going on.

```kotlin
fun buildGreeting(context: AriaContext): String {
    val timeGreeting = when (context.timeBucket) {
        TimeBucket.EARLY_MORNING, TimeBucket.MORNING -> "Good morning"
        TimeBucket.MIDDAY, TimeBucket.AFTERNOON -> "Good afternoon"
        TimeBucket.EVENING -> "Good evening"
        TimeBucket.NIGHT -> "Good night"
    }

    return when {
        context.upcomingCalendarEvents.isNotEmpty() -> {
            val next = context.upcomingCalendarEvents.first()
            "$timeGreeting · ${next.title} in ${next.minutesUntil}m"
        }
        context.detectedActivity == DetectedActivity.IN_VEHICLE ->
            "$timeGreeting · Drive safe"
        else -> timeGreeting
    }
}
```

### Predicted Apps Row

```kotlin
@Composable
fun PredictedAppsRow(
    apps: List<PredictedApp>,
    onAppClick: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(
            items = apps.take(6),
            key = { it.packageName }
        ) { app ->
            AppIconWithLabel(
                packageName = app.packageName,
                label = app.label,
                onClick = { onAppClick(app.packageName) },
                modifier = Modifier.animateItemPlacement(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )
            )
        }
    }
}
```

- `animateItemPlacement` handles smooth reordering when predictions change
- Do NOT animate on every unlock — only when the underlying order changes
- Icons only by default, no labels, consistent with dock style

### LLM Editorial System Prompt

Called once per context change, not per unlock.

```kotlin
fun buildEditorialSystemPrompt(context: AriaContext): String = """
    You are ARIA's (an android launcher) editorial engine. Your job is to decide what appears on the
    user's home screen right now, based on their current context and signals
    from ARIA's prediction engine.

    Current context:
    - Time: ${context.formattedTime} (${context.timeBucket})
    - Day: ${context.dayOfWeek}
    - Location: ${context.locationHint}
    - Detected activity: ${context.detectedActivity}
    - Weather: ${context.weatherSummary}
    - Active weather alerts: ${context.weatherAlerts.joinToString("; ")}
    - Upcoming calendar events: ${context.upcomingEvents.joinToString("; ")}
    - Recently used apps: ${context.recentApps.joinToString(", ")}
    - Current venue: ${context.venueName ?: "unknown"}
    - Predicted top apps (from ML model): ${context.topPredictions.joinToString(", ")}
    - Active user rules that fired: ${context.firedRules.joinToString("; ")}

    Respond ONLY with valid JSON. No markdown, no explanation, no preamble:

    {
      "brief": [
        {
          "type": "<valid type>",
          "icon": "<material symbol name>",
          "headline": "<max 6 words — ARIA's judgment, not raw data>",
          "subtext": "<max 12 words, or null>",
          "action": { "label": "<max 3 words>", "intentUri": "<uri or null>" }
        }
      ]
    }

    Valid types: alert_assessed, reminder_nudge, calendar_event, media_resume,
    proactive_suggestion, venue_card, live_data_card

    Rules:
    - Maximum 5 items. Minimum 0 — empty is better than noisy.
    - Headlines must be ARIA's judgment, not forwarded data.
      BAD: "Flood Advisory issued March 15 at 9:02PM CDT until March 16"
      GOOD: "Flood advisory — your area isn't affected"
    - Never show more than one weather item.
    - Calendar events within 30 minutes always appear.
    - If a user rule fired, its corresponding action takes priority.
    - When in doubt, show less.
""".trimIndent()
```

---

## Repository Structure

```
aria-launcher/                        ← root IS the app module (Groovy build.gradle)
├── src/                              ← main sourceSet (AOSP Launcher3)
├── src_plugins/                      ← main sourceSet (plugins)
├── lawnchair/
│   ├── src/
│   │   └── com/aria/launcher/
│   │       ├── aria/                 ← ARIA feature layer (lawn sourceSet)
│   │       │   ├── data/             ← UsageStats, Room DB, Skill models
│   │       │   ├── engine/           ← Prediction engine, AriaContext, rules
│   │       │   ├── llm/              ← LLM provider abstraction layer
│   │       │   ├── agent/            ← App interaction / agentic tasks
│   │       │   ├── chat/             ← Embedded chat UI (Compose)
│   │       │   ├── scheduler/        ← Nightly WorkManager jobs
│   │       │   └── ui/              ← Home screen, Brief, composables
│   │       │       └── brief/       ← Brief system (sources, composables, aggregator)
│   │       └── AriaApplication.kt
│   └── AndroidManifest.xml           ← ARIA/Lawnchair manifest (merged)
├── AndroidManifest-common.xml        ← AOSP base manifest
├── build.gradle                      ← app module build file
└── docs/
    └── ARIA_PLAN.md                  ← this file
```

---

## Tech Stack

| Component        | Technology                                                          |
|------------------|---------------------------------------------------------------------|
| Language         | Kotlin 2.3.10                                                       |
| UI               | Jetpack Compose                                                     |
| Base             | Lawnchair 2 fork (16-dev branch)                                   |
| Database         | Room 2.8.4                                                          |
| Background jobs  | WorkManager 2.10.0                                                  |
| DI               | Hilt/Dagger 2.59.2                                                  |
| Async            | Coroutines + Flow                                                   |
| HTTP             | OkHttp 5.3.2 (via Retrofit 3.0.0)                                  |
| LLM layer        | OkHttp direct (SSE streaming) — not Retrofit                        |
| App interaction  | AccessibilityService API, NotificationListenerService               |
| Context signals  | UsageStatsManager, ActivityRecognitionClient, WifiManager, Calendar |
| Build system     | Gradle 9.4.0, AGP 9.1.0, libs.versions.toml catalog                |
| Min SDK          | API 26 (Android 8)                                                  |
| Target SDK       | API 36 (Android 16)                                                 |

---

## LLM Provider Strategy

| User Setup | ARIA Provider | Auth | Cost |
|------------|--------------|------|------|
| Google AI Studio (Gemini) | `GeminiProvider` | API key | Free tier |
| Claude Pro/Max subscription | `ClaudeProvider` | OAuth token via QR pairing | Subscription |
| Claude API key | `ClaudeProvider` | `x-api-key` header | Per-token |
| Self-hosted Ollama | `OllamaProvider` | Server URL | Free |
| OpenRouter | `OpenAICompatibleProvider` | API key | Per-token |
| OpenAI / GPT-4o | `OpenAICompatibleProvider` | API key | Per-token |

### Provider Architecture

- **`LlmProvider`** — Interface: `complete()`, `streamComplete(): Flow<String>`, `completeWithTools()`
- **`ClaudeProvider`** — Anthropic Messages API, SSE streaming, native tool calling. OAuth + API key auth.
- **`GeminiProvider`** — Native Gemini API (free tier).
- **`OllamaProvider`** — Remote server (user-configured URL), NDJSON streaming
- **`OpenAICompatibleProvider`** — Generic chat completions endpoint (OpenRouter, OpenAI, etc.)
- **`LlmProviderManager`** — Runtime provider selection persisted to DataStore
- **`AriaPrompts`** — System prompt builder with context injection + tool definitions

---

## Key Architectural Decisions

1. **Hybrid Brief generation**: BriefDataSources produce candidate BriefItems. The LLM editorial engine receives candidates + raw context, then reranks, filters, rewrites headlines, AND can generate new items no source provided (proactive suggestions). Heuristic fallback when no LLM is configured.

2. **In-memory Brief cache**: The Brief lives in a StateFlow on AriaHomeState. Survives between unlocks, lost on process death (rebuilt on next context change, ~1-3s). No Room persistence for the Brief.

3. **Context change debounce**: AriaContextMonitor debounces signal changes (~30s after last signal) before emitting a new AriaContext. Prevents rapid-fire LLM editorial calls.

4. **SkillBridgeSource sunset**: Session 8 introduces SkillBridgeSource for backward compat. By Session 9, native BriefDataSources cover calendar, notifications, media, venue. SkillBridgeSource deprecated after Session 9.

---

## MCP Integration Architecture

> **Implementation Priority: PHASE 8+ — Architecture Only For Now**
>
> Do NOT implement MCP during Sessions 7-14. The purpose of this section is to
> ensure that decisions made in Sessions 1–7 do not accidentally close off the
> architecture needed to add MCP support cleanly later.
>
> When you see a "Future Seam" callout, that is an instruction to leave a specific
> extension point in the current code — an interface, an abstraction boundary,
> or a placeholder — that will allow MCP to slot in later without requiring a rewrite.

### What MCP Unlocks

Today, ARIA's agent layer works via Android Intents and deep links. MCP is categorically different: it gives ARIA the ability to *take actions inside apps* and *fetch live data from services* without the user opening anything.

| Without MCP | With MCP |
|---|---|
| "Open Spotify" (launches app) | "Add this to your workout playlist" (done silently) |
| "Open Home Assistant" (launches app) | "Your back door is unlocked" (card, tap to lock) |
| "Open Calendar" (launches app) | Morning brief card built from actual event data |
| "Open Gmail" (launches app) | "You have 3 emails from Ava" (summarized on card) |
| "Here are directions" (opens Maps) | ETA to next calendar event shown on home screen |

The difference is ARIA becoming an *actor* rather than a *navigator*.

### Mental Model: ARIA as Universal MCP Client

ARIA does not host MCP servers. It is a client that connects to servers wherever they live:

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

When on home WiFi, ARIA has access to the full home server capability set. When away, it falls back to cloud endpoints and on-device capabilities only. This graceful degradation is a first-class requirement, not an afterthought.

### Architecture Overview

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

- **Registry**: Knows which MCP servers *might* exist for installed apps. Ships with ARIA, updated via community contributions. Pure static data.
- **Discovery**: Checks at runtime which servers are actually reachable. Runs on network change events, not on every unlock. Results cached in Room.
- **Capability Cache**: Stores the tool manifests (list of available tools and their schemas) for each reachable server. Prevents querying servers on every request.
- **Executor**: Takes a tool name + parameters, calls the right server, returns the result. Called by both the LLM chat layer and the rule engine.

### Four MCP Seams (defined early, implemented later)

#### Seam 1: ActionExecutor interface

Currently, actions execute via Android Intents and deep links. MCP will be a second execution pathway. Define a shared interface so both paths are interchangeable:

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
        // Accessibility preferred over Intent for richer interaction
        // Intent is always the fallback
        return capable.first().execute(action)
    }
}
```

When MCP arrives in Phase 8, add `McpExecutor : ActionExecutor` and register it in the dispatcher at higher priority than IntentExecutor for actions it can handle.

#### Seam 2: BriefDataSource interface

MCP will add live data from external services (Spotify now playing, Home Assistant sensor states, Gmail unread count) as additional card data sources:

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

#### Seam 3: RuleAction.FetchData

Already included in the RuleAction sealed class above. It does nothing yet — no executor handles it until Phase 8. The rule compiler's system prompt includes this action type so users can write rules like "when I get home, fetch the Home Assistant door sensor and show a card if it's unlocked." The rule will compile correctly. The executor will log "no handler for FetchData" until Phase 8 without crashing.

#### Seam 4: AriaSkill interface

Skills are the composable automation unit — a named sequence of data fetches and actions that can be triggered from the chat, a rule, or proactively:

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

### Data Models (defined, not persisted until needed)

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

### Phase 8 Implementation Sketch (future reference)

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
    suspend fun scanAndUpdate(networkContext: NetworkContext)
    private suspend fun discoverHomeServer(): String?  // returns endpoint URL
}
```

**8.3 — McpExecutor**
- Implements `ActionExecutor` (Seam 1)
- LLM decides which tool to call from the capability cache
- Handles `RuleAction.FetchData` (Seam 3)

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

Home Assistant already has an official MCP server. First end-to-end test of the full stack:
- Discovery finds HA server on home WiFi
- Capability cache lists HA tools (get_state, call_service, etc.)
- Rule: "When I get home, fetch door sensor states and show a card for any that are open/unlocked"
- McpExecutor calls `homeassistant.get_state` with entity IDs
- BriefItem card rendered with lock/unlock action button

**8.5 — ClawHub / community registry integration**
- Define ClawHub registry format (simple JSON, hosted on GitHub)
- ARIA polls for registry updates weekly
- Users can submit new server entries via PR (community model)
- In-app browser for discovering available skills

### MCP Security Model

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

Auth tokens are stored in Android's `EncryptedSharedPreferences`, never in Room directly. The `McpServer.authToken` field in the Room entity stores only a key reference, not the token itself.

### What MCP Enables Long-Term

Once this layer is built, the capabilities that become possible without any additional core ARIA development:

- **Home screen as dashboard**: Hue lights state, door sensors, Plex now playing, server health — all as live BriefItems from HA and Proxmox MCP servers, surfaced only when relevant
- **Truly silent automation**: "When I leave work WiFi, turn off my office desk lamp" — MCP rule that calls HA directly, no app opened, no card shown
- **Cross-app workflows**: "After my last meeting ends, send Ava a message saying I'm leaving soon and start navigation home" — calendar MCP reads the event, messaging MCP drafts the text, Maps intent handles navigation
- **Community skills**: A "Morning Briefing" skill on something like ClawHub that chains calendar + weather + news + HA sensors into a single rich card, authored by the community, installed in one tap

---

## Intelligence Layer

### Three-Layer Venue Intelligence Stack

```
Layer 1: SSID Pattern Matching (instant, no ML, no LLM)
         ↓ if unrecognized
Layer 2: LLM SSID Classification (runs ONCE per novel SSID, cached forever)
         ↓
Layer 3: Venue Affinity Map (default app suggestions by venue category)
         ↓ yields to →
TFLite Model (overrides defaults once personal behavior is observed)
```

#### Data Models

```kotlin
// Room entities

@Entity(tableName = "ssid_classifications")
data class SsidClassification(
    @PrimaryKey val ssidHash: String,      // SHA-256 of SSID for privacy
    val rawSsid: String,                    // stored locally only
    val venueCategory: VenueCategory,
    val visitContext: VisitContext = VisitContext.UNKNOWN,
    val visitCount: Int = 0,
    val averageVisitDurationMinutes: Long = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val userConfirmed: Boolean = false      // true if user explicitly labeled it
)

enum class VenueCategory {
    FAST_FOOD, COFFEE, RETAIL, HEALTHCARE, HOTEL,
    TRAVEL, OFFICE, EDUCATION, ENTERTAINMENT,
    HOME, WORK,   // user-designated
    UNKNOWN
}

enum class VisitContext {
    LIKELY_WORKPLACE,   // high frequency, long duration, weekday pattern
    LIKELY_APPOINTMENT, // calendar event matches, short visit
    LIKELY_VISITOR,     // infrequent, short
    UNKNOWN
}

data class AppAffinity(
    val packageName: String,
    val relevanceScore: Float   // 0.0–1.0, only surfaces if app is installed
)
```

#### Layer 1: SSID Pattern Matching

```kotlin
object SsidPatternMatcher {

    // Returns null if no pattern matches — triggers Layer 2
    fun classify(ssid: String): VenueCategory? {
        val patterns = mapOf(
            Regex("mcdonald|mcdonalds", IGNORE_CASE)              to VenueCategory.FAST_FOOD,
            Regex("starbucks", IGNORE_CASE)                        to VenueCategory.COFFEE,
            Regex("dunkin", IGNORE_CASE)                           to VenueCategory.COFFEE,
            Regex("target|walmart|costco|kroger|walgreens",
                IGNORE_CASE)                                       to VenueCategory.RETAIL,
            Regex("hospital|medical|health|clinic|mayo|cedar",
                IGNORE_CASE)                                       to VenueCategory.HEALTHCARE,
            Regex("hilton|marriott|hyatt|holiday.?inn|westin|
                sheraton|hampton.?inn", IGNORE_CASE)               to VenueCategory.HOTEL,
            Regex("airport|united|delta|southwest|american.air",
                IGNORE_CASE)                                       to VenueCategory.TRAVEL,
            Regex("university|college|edu|student",
                IGNORE_CASE)                                       to VenueCategory.EDUCATION
        )
        return patterns.entries.firstOrNull { it.key.containsMatchIn(ssid) }?.value
    }
}
```

#### Layer 2: LLM SSID Classification (runs once, cached forever)

```kotlin
class SsidClassificationService(
    private val llmProvider: LlmProvider,
    private val dao: SsidClassificationDao
) {
    suspend fun classifyIfNeeded(ssid: String): VenueCategory {
        val hash = ssid.sha256()

        // Check cache first
        dao.getByHash(hash)?.let { return it.venueCategory }

        // Try pattern matching
        SsidPatternMatcher.classify(ssid)?.let { category ->
            dao.insert(SsidClassification(hash, ssid, category))
            return category
        }

        // LLM fallback — runs exactly once per novel SSID
        val category = askLlmToClassify(ssid)
        dao.insert(SsidClassification(hash, ssid, category))
        return category
    }

    private suspend fun askLlmToClassify(ssid: String): VenueCategory {
        val response = llmProvider.complete(
            systemPrompt = """
                Classify this WiFi network name into exactly one venue category.
                Respond with ONLY one of these exact strings, nothing else:
                FAST_FOOD, COFFEE, RETAIL, HEALTHCARE, HOTEL, TRAVEL,
                OFFICE, EDUCATION, ENTERTAINMENT, UNKNOWN
            """.trimIndent(),
            messages = listOf(ChatMessage(Role.USER, "WiFi network name: \"$ssid\""))
        )
        return try {
            VenueCategory.valueOf(response.trim())
        } catch (e: IllegalArgumentException) {
            VenueCategory.UNKNOWN
        }
    }
}
```

#### Layer 3: Venue Affinity Map

Only surfaces apps that are actually installed. Never suggests uninstalled apps.

```kotlin
object VenueAffinityMap {

    val defaultAffinities: Map<VenueCategory, List<AppAffinity>> = mapOf(

        VenueCategory.FAST_FOOD to listOf(
            AppAffinity("com.mcdonalds.app", 0.90f),
            AppAffinity("com.starbucks.mobilecard", 0.85f),
            AppAffinity("com.chickfila.cfaone", 0.85f),
            AppAffinity("com.tacobell.mobile", 0.80f),
            AppAffinity("com.subway.mobile", 0.80f)
        ),

        VenueCategory.COFFEE to listOf(
            AppAffinity("com.starbucks.mobilecard", 0.95f),
            AppAffinity("com.dunkin.mobile", 0.90f)
        ),

        VenueCategory.HEALTHCARE to listOf(
            AppAffinity("org.mychart.android", 0.90f),
            AppAffinity("com.anthem.android", 0.75f),
            AppAffinity("com.cigna.mobile", 0.75f),
            AppAffinity("com.aetna.mobile", 0.75f),
            AppAffinity("com.unitedhealthcare.member", 0.75f),
            AppAffinity("com.bluecrossma.android", 0.70f)
        ),

        VenueCategory.HOTEL to listOf(
            AppAffinity("com.hilton.android", 0.90f),
            AppAffinity("com.marriott.mrt", 0.90f),
            AppAffinity("com.ihg.apps.android", 0.85f),
            AppAffinity("com.hyatt.android", 0.85f)
        ),

        VenueCategory.TRAVEL to listOf(
            AppAffinity("com.flightaware.flightaware", 0.85f),
            AppAffinity("com.united.mobile.android.united", 0.90f),
            AppAffinity("com.aa.android", 0.90f),
            AppAffinity("com.delta", 0.90f),
            AppAffinity("com.southwest.mobile", 0.85f),
            AppAffinity("com.tsa.precheck", 0.70f)
        ),

        VenueCategory.RETAIL to listOf(
            AppAffinity("com.target.ui", 0.85f),
            AppAffinity("com.walmart.android", 0.85f),
            AppAffinity("com.costco.android", 0.80f)
        )
    )

    // Context-aware override: same venue, different role
    val healthcareByVisitContext: Map<VisitContext, List<AppAffinity>> = mapOf(
        VisitContext.LIKELY_WORKPLACE to listOf(
            // Suppress patient apps, surface work tools
            AppAffinity("com.slack", 0.80f),
            AppAffinity("com.microsoft.teams", 0.80f),
            AppAffinity("com.workday.android", 0.75f)
        ),
        VisitContext.LIKELY_APPOINTMENT to listOf(
            AppAffinity("org.mychart.android", 0.95f),
            AppAffinity("com.anthem.android", 0.80f)
        ),
        VisitContext.LIKELY_VISITOR to listOf(
            AppAffinity("org.mychart.android", 0.75f)
        )
    )

    fun getAffinities(
        category: VenueCategory,
        context: VisitContext,
        installedPackages: Set<String>
    ): List<AppAffinity> {
        val base = when {
            category == VenueCategory.HEALTHCARE && context != VisitContext.UNKNOWN ->
                healthcareByVisitContext[context] ?: defaultAffinities[category]
            else -> defaultAffinities[category]
        } ?: emptyList()

        // Critical: only return apps that are actually installed
        return base.filter { it.packageName in installedPackages }
    }
}
```

#### Visit Context Inference

```kotlin
fun inferVisitContext(
    venueCategory: VenueCategory,
    visitCount: Int,
    averageVisitDurationMinutes: Long,
    dayType: DayType,
    calendarEvents: List<CalendarEvent>
): VisitContext {

    // High-frequency + long duration + weekday = probably work
    if (visitCount > 8
        && averageVisitDurationMinutes > 240
        && dayType == DayType.WEEKDAY) {
        return VisitContext.LIKELY_WORKPLACE
    }

    // Calendar has medical appointment language
    val appointmentKeywords = Regex(
        "appointment|doctor|dr\\.|checkup|dentist|therapy|infusion|procedure",
        IGNORE_CASE
    )
    if (calendarEvents.any { appointmentKeywords.containsMatchIn(it.title) }) {
        return VisitContext.LIKELY_APPOINTMENT
    }

    // Infrequent + short = visitor or one-off appointment
    if (visitCount < 3 && averageVisitDurationMinutes < 120) {
        return VisitContext.LIKELY_VISITOR
    }

    return VisitContext.UNKNOWN
}
```

#### How Cold Start Yields to TFLite

The affinity map provides a `prior score` for each app. TFLite's output is a `learned score`. The final prediction score blends both, weighted by how much personal data has been collected at that venue:

```kotlin
fun blendScores(
    priorScore: Float,
    learnedScore: Float,
    observationsAtVenue: Int
): Float {
    // Weight shifts from prior → learned as observations accumulate
    // After ~20 visits, learned score dominates entirely
    val learnedWeight = (observationsAtVenue / 20f).coerceIn(0f, 1f)
    val priorWeight = 1f - learnedWeight
    return (priorScore * priorWeight) + (learnedScore * learnedWeight)
}
```

### Rule Engine

Users tell ARIA rules in plain English via chat. The LLM compiles the natural language rule into a structured `AriaRule` once. At runtime, rule evaluation is pure Kotlin — no LLM involved.

```
User: "Always show me the McDonald's app when I'm on McDonald's wifi"
  → LLM compiles once → AriaRule(trigger=WifiSsid, action=SurfaceApp)
  → stored in Room → evaluated at runtime with zero LLM calls
```

#### Rule Data Models

```kotlin
@Entity(tableName = "aria_rules")
data class AriaRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val naturalLanguageSource: String,      // original user text, for display
    val trigger: RuleTrigger,               // what condition activates this rule
    val action: RuleAction,                 // what ARIA does when triggered
    val confidence: Float = 1.0f,           // LLM's confidence in its interpretation
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastTriggeredAt: Long? = null,
    val triggerCount: Int = 0,
    val needsUserConfirmation: Boolean = false  // true for destructive actions
)

// Triggers — what conditions activate a rule
sealed class RuleTrigger {
    data class WifiSsidTrigger(
        val pattern: String,
        val matchType: MatchType    // EXACT, CONTAINS, STARTS_WITH, REGEX
    ) : RuleTrigger()

    data class VenueCategoryTrigger(
        val category: VenueCategory
    ) : RuleTrigger()

    data class TimeTrigger(
        val startHour: Int,
        val endHour: Int,
        val daysOfWeek: List<Int>?  // null = every day
    ) : RuleTrigger()

    data class AppOpenedTrigger(
        val packageName: String
    ) : RuleTrigger()

    data class CalendarEventTrigger(
        val titleKeywords: List<String>,
        val minutesBefore: Int = 15
    ) : RuleTrigger()

    data class LocationTrigger(
        val lat: Double,
        val lng: Double,
        val radiusMeters: Float
    ) : RuleTrigger()

    data class AndroidAutoTrigger(
        val connectedCarName: String? = null    // null = any car
    ) : RuleTrigger()

    data class CompoundTrigger(
        val triggers: List<RuleTrigger>,
        val operator: LogicOperator            // AND, OR
    ) : RuleTrigger()
}

// Actions — what ARIA does when a rule fires
sealed class RuleAction {
    data class SurfaceApp(
        val packageName: String,
        val priority: SurfacePriority          // ALWAYS_SHOW, BOOST, PIN_TO_DOCK
    ) : RuleAction()

    data class SuppressApp(
        val packageName: String
    ) : RuleAction()

    data class ShowCard(
        val cardType: String,
        val headline: String,
        val subtext: String?,
        val intentUri: String?
    ) : RuleAction()

    data class OpenApp(
        val packageName: String,
        val intentUri: String? = null          // deep link if specified
    ) : RuleAction()

    data class SendMessage(
        val contactName: String,
        val messageTemplate: String            // supports {time}, {location} tokens
    ) : RuleAction()

    data class SetSpace(
        val spaceName: String
    ) : RuleAction()

    data class RunSkill(
        val skillId: String,
        val params: Map<String, String>
    ) : RuleAction()

    data class FetchData(
        val serverId: String,
        val toolName: String,
        val params: Map<String, String>,
        val onSuccess: RuleAction,      // action to take with the result
        val onFailure: RuleAction?      // optional fallback
    ) : RuleAction()
}

enum class MatchType { EXACT, CONTAINS, STARTS_WITH, REGEX }
enum class SurfacePriority { ALWAYS_SHOW, BOOST, PIN_TO_DOCK }
enum class LogicOperator { AND, OR }
```

#### LLM Rule Compiler

This is the only place the LLM is involved in the rule system. It runs once when the user creates or edits a rule.

```kotlin
class AriaRuleCompiler(private val llmProvider: LlmProvider) {

    suspend fun compile(userInput: String): RuleCompilationResult {
        val systemPrompt = """
            You are a rule compiler for ARIA, an Android launcher assistant.
            The user will describe a rule in natural language.
            Your job is to convert it into a structured JSON rule object.

            Available trigger types:
            - WifiSsidTrigger: { "type": "wifi_ssid", "pattern": "string",
              "matchType": "EXACT|CONTAINS|STARTS_WITH|REGEX" }
            - VenueCategoryTrigger: { "type": "venue_category",
              "category": "FAST_FOOD|COFFEE|RETAIL|HEALTHCARE|HOTEL|TRAVEL|OFFICE|EDUCATION|ENTERTAINMENT" }
            - TimeTrigger: { "type": "time", "startHour": 0-23, "endHour": 0-23,
              "daysOfWeek": [1-7] or null for every day }
            - AppOpenedTrigger: { "type": "app_opened", "packageName": "com.example.app" }
            - CalendarEventTrigger: { "type": "calendar_event",
              "titleKeywords": ["keyword1"], "minutesBefore": 15 }
            - AndroidAutoTrigger: { "type": "android_auto", "connectedCarName": "string or null" }
            - CompoundTrigger: { "type": "compound", "operator": "AND|OR",
              "triggers": [...] }

            Available action types:
            - SurfaceApp: { "type": "surface_app", "packageName": "com.example.app",
              "priority": "ALWAYS_SHOW|BOOST|PIN_TO_DOCK" }
            - SuppressApp: { "type": "suppress_app", "packageName": "com.example.app" }
            - ShowCard: { "type": "show_card", "cardType": "string",
              "headline": "string", "subtext": "string or null", "intentUri": "string or null" }
            - OpenApp: { "type": "open_app", "packageName": "com.example.app",
              "intentUri": "string or null" }
            - SendMessage: { "type": "send_message", "contactName": "string",
              "messageTemplate": "string" }
            - SetSpace: { "type": "set_space", "spaceName": "string" }

            Respond ONLY with valid JSON in this format:
            {
              "trigger": { ... },
              "action": { ... },
              "confidence": 0.0-1.0,
              "needsUserConfirmation": true/false,
              "humanReadableSummary": "When [trigger], ARIA will [action]",
              "clarificationNeeded": "string or null"
            }

            Set needsUserConfirmation to true for any action that sends messages,
            makes purchases, or opens apps automatically without user initiation.
            Set confidence below 0.8 if the rule is ambiguous.
            Set clarificationNeeded if you need more information to compile the rule.
        """.trimIndent()

        val response = llmProvider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(ChatMessage(Role.USER, userInput))
        )

        return parseCompilationResult(response, userInput)
    }

    private fun parseCompilationResult(
        json: String,
        originalInput: String
    ): RuleCompilationResult {
        return try {
            val parsed = Json.parseToJsonElement(json).jsonObject
            val confidence = parsed["confidence"]?.jsonPrimitive?.float ?: 0.5f
            val clarification = parsed["clarificationNeeded"]?.jsonPrimitive?.content
            val summary = parsed["humanReadableSummary"]?.jsonPrimitive?.content ?: ""
            val needsConfirmation = parsed["needsUserConfirmation"]
                ?.jsonPrimitive?.boolean ?: false

            if (clarification != null) {
                RuleCompilationResult.NeedsClarification(clarification)
            } else if (confidence < 0.7f) {
                RuleCompilationResult.LowConfidence(summary, confidence)
            } else {
                val trigger = parseTrigger(parsed["trigger"]!!.jsonObject)
                val action = parseAction(parsed["action"]!!.jsonObject)
                RuleCompilationResult.Success(
                    rule = AriaRule(
                        naturalLanguageSource = originalInput,
                        trigger = trigger,
                        action = action,
                        confidence = confidence,
                        needsUserConfirmation = needsConfirmation
                    ),
                    humanReadableSummary = summary
                )
            }
        } catch (e: Exception) {
            RuleCompilationResult.ParseError("Could not understand that rule. Try rephrasing.")
        }
    }
}

sealed class RuleCompilationResult {
    data class Success(val rule: AriaRule, val humanReadableSummary: String)
        : RuleCompilationResult()
    data class NeedsClarification(val question: String)
        : RuleCompilationResult()
    data class LowConfidence(val summary: String, val confidence: Float)
        : RuleCompilationResult()
    data class ParseError(val message: String)
        : RuleCompilationResult()
}
```

#### Rule Evaluator (runs at every context change — zero LLM calls)

```kotlin
class AriaRuleEvaluator(private val dao: AriaRuleDao) {

    fun evaluate(context: AriaContext): List<RuleAction> {
        val enabledRules = dao.getEnabledRules()   // synchronous Room query
        return enabledRules
            .filter { matches(it.trigger, context) }
            .map { it.action }
            .also { matched ->
                // Update trigger counts asynchronously
                matched.forEach { dao.incrementTriggerCount(it) }
            }
    }

    private fun matches(trigger: RuleTrigger, context: AriaContext): Boolean {
        return when (trigger) {
            is RuleTrigger.WifiSsidTrigger -> {
                val ssid = context.currentWifiSsid ?: return false
                when (trigger.matchType) {
                    MatchType.EXACT -> ssid.equals(trigger.pattern, ignoreCase = true)
                    MatchType.CONTAINS -> ssid.contains(trigger.pattern, ignoreCase = true)
                    MatchType.STARTS_WITH -> ssid.startsWith(trigger.pattern, ignoreCase = true)
                    MatchType.REGEX -> Regex(trigger.pattern, IGNORE_CASE).containsMatchIn(ssid)
                }
            }
            is RuleTrigger.VenueCategoryTrigger ->
                context.currentVenueCategory == trigger.category

            is RuleTrigger.TimeTrigger -> {
                val hour = context.hourOfDay
                val dow = context.dayOfWeek
                val inHourRange = hour in trigger.startHour..trigger.endHour
                val inDayRange = trigger.daysOfWeek?.contains(dow) ?: true
                inHourRange && inDayRange
            }
            is RuleTrigger.AppOpenedTrigger ->
                context.currentForegroundApp == trigger.packageName

            is RuleTrigger.CalendarEventTrigger -> {
                val upcoming = context.upcomingCalendarEvents
                    .filter { it.startTimeMs - System.currentTimeMillis()
                        < trigger.minutesBefore * 60 * 1000 }
                upcoming.any { event ->
                    trigger.titleKeywords.any { kw ->
                        event.title.contains(kw, ignoreCase = true)
                    }
                }
            }
            is RuleTrigger.AndroidAutoTrigger ->
                context.isConnectedToAutomotive &&
                (trigger.connectedCarName == null ||
                    context.automotiveDeviceName == trigger.connectedCarName)

            is RuleTrigger.CompoundTrigger -> when (trigger.operator) {
                LogicOperator.AND -> trigger.triggers.all { matches(it, context) }
                LogicOperator.OR -> trigger.triggers.any { matches(it, context) }
            }
        }
    }
}
```

#### Chat Rule Routing

When the user types a rule in the ARIA chat bar, detect it and route to the compiler instead of the general LLM:

```kotlin
class AriaChatHandler(
    private val llmProvider: LlmProvider,
    private val ruleCompiler: AriaRuleCompiler,
    private val ruleDao: AriaRuleDao
) {
    // Keywords that signal rule creation intent
    private val ruleIntentPatterns = listOf(
        Regex("always (show|open|surface|display)", IGNORE_CASE),
        Regex("when(ever)? (i|you|my|the)", IGNORE_CASE),
        Regex("every time", IGNORE_CASE),
        Regex("from now on", IGNORE_CASE),
        Regex("if (you see|i'm at|i am at|connected to)", IGNORE_CASE),
        Regex("it means", IGNORE_CASE),
        Regex("go ahead and", IGNORE_CASE),
        Regex("automatically", IGNORE_CASE),
        Regex("remember (that|to|when)", IGNORE_CASE)
    )

    suspend fun handle(userInput: String): ChatResponse {
        return if (isRuleCreationIntent(userInput)) {
            handleRuleCreation(userInput)
        } else {
            handleGeneralChat(userInput)
        }
    }

    private fun isRuleCreationIntent(input: String): Boolean =
        ruleIntentPatterns.any { it.containsMatchIn(input) }

    private suspend fun handleRuleCreation(userInput: String): ChatResponse {
        return when (val result = ruleCompiler.compile(userInput)) {
            is RuleCompilationResult.Success -> {
                ChatResponse(
                    text = "Got it. Here's what I'll do:\n\n" +
                        "**${result.humanReadableSummary}**\n\n" +
                        "Should I save this rule?",
                    confirmationAction = ConfirmationAction.SaveRule(result.rule),
                    suggestedReplies = listOf("Yes, save it", "No", "Change it")
                )
            }
            is RuleCompilationResult.NeedsClarification ->
                ChatResponse(text = result.question)

            is RuleCompilationResult.LowConfidence ->
                ChatResponse(
                    text = "I think you mean: ${result.summary}\n\n" +
                        "Is that right? (Confidence: ${(result.confidence * 100).toInt()}%)",
                    suggestedReplies = listOf("Yes, that's right", "Not quite")
                )

            is RuleCompilationResult.ParseError ->
                ChatResponse(text = result.message)
        }
    }
}
```

#### Rule Management UI

```kotlin
@Composable
fun RulesScreen(rules: List<AriaRule>, onToggle: (AriaRule) -> Unit,
                onDelete: (AriaRule) -> Unit) {
    LazyColumn {
        items(rules) { rule ->
            RuleCard(
                naturalLanguage = rule.naturalLanguageSource,
                summary = rule.humanReadableSummary,
                isEnabled = rule.isEnabled,
                triggerCount = rule.triggerCount,
                lastTriggered = rule.lastTriggeredAt,
                onToggle = { onToggle(rule) },
                onDelete = { onDelete(rule) }
            )
        }
    }
}
// Access via: ARIA chat → "show my rules" or Settings → Rules
```

#### Example Rules (test these end-to-end)

```
"Always show me the McDonald's app when I'm on McDonald's wifi"
→ WifiSsidTrigger(CONTAINS "mcdonald") + SurfaceApp(com.mcdonalds.app, ALWAYS_SHOW)

"When I connect to my car, switch to commute mode and open Spotify"
→ AndroidAutoTrigger + SetSpace("Commute") [note: OpenApp requires confirmation]

"When you see the wifi network 'ADVOCATE_GUEST', it means I'm at the hospital
 for an appointment — show me MyChart and my insurance card app"
→ WifiSsidTrigger(EXACT "ADVOCATE_GUEST") + SurfaceApp(org.mychart.android, ALWAYS_SHOW)
   [note: LLM will compile this as two SurfaceApp actions via a CompoundAction]

"Remind me to open the Target app whenever I'm at Target"
→ VenueCategoryTrigger(RETAIL) + ShowCard("reminder", "You're at Target", null, null)

"Every weekday morning between 8 and 9, show me my work calendar"
→ TimeTrigger(8, 9, [Mon-Fri]) + SurfaceApp(com.google.android.calendar, ALWAYS_SHOW)

"When I open my bank app, automatically open the calculator too"
→ AppOpenedTrigger("com.chase.sig.android") + SurfaceApp(com.google.android.calculator, BOOST)
   [note: needsUserConfirmation=false since BOOST just elevates, not auto-opens]
```

### App Chain Detection

Finds apps consistently opened within N minutes of each other. When the trigger app is active, boost the follow-up app's prediction score.

```kotlin
class AppChainDetector(private val dao: UsageEventDao) {

    // Finds apps that are consistently opened within N minutes of a trigger app
    suspend fun detectChains(
        windowMinutes: Int = 3,
        minOccurrences: Int = 5
    ): List<AppChain> {
        val events = dao.getRecentEvents(days = 30)
        val chains = mutableMapOf<Pair<String, String>, Int>()

        for (i in 0 until events.size - 1) {
            val current = events[i]
            val next = events[i + 1]
            val deltaMs = next.timestamp - current.timestamp

            if (deltaMs < windowMinutes * 60 * 1000
                && current.packageName != next.packageName) {
                val pair = Pair(current.packageName, next.packageName)
                chains[pair] = (chains[pair] ?: 0) + 1
            }
        }

        return chains
            .filter { it.value >= minOccurrences }
            .map { AppChain(trigger = it.key.first, followUp = it.key.second,
                occurrences = it.value) }
    }
}

data class AppChain(
    val trigger: String,    // packageName of the app that starts the chain
    val followUp: String,   // packageName that consistently follows
    val occurrences: Int
)
```

When the trigger app is currently in the foreground or was just opened, boost the followUp app's prediction score. This handles the "bank → calculator → bank" pattern without needing it to be in the feature vector explicitly.

### LiteRT Accommodation

#### Feature Vector (per app, per prediction request)

```kotlin
data class PredictionFeatures(
    // Time context
    val hourOfDayNormalized: Float,        // 0.0–1.0 (hour / 23)
    val dayOfWeekOneHot: FloatArray,        // size 7
    val isWeekend: Float,                   // 0 or 1

    // Location context
    val locationHintOneHot: FloatArray,     // HOME, WORK, COMMUTE, VENUE, UNKNOWN → size 5
    val venueCategoryOneHot: FloatArray,    // size 12 (one per VenueCategory)
    val visitContextOneHot: FloatArray,     // size 4 (one per VisitContext)

    // Device context
    val isCharging: Float,                  // 0 or 1
    val detectedActivityOneHot: FloatArray, // STILL, WALKING, IN_VEHICLE, etc. → size 5
    val isConnectedToAutomotive: Float,     // 0 or 1

    // App-specific history
    val minutesSinceLastUse: Float,         // normalized 0–1 (capped at 24h)
    val openCountLast7Days: Float,          // normalized 0–1 (capped at 20)
    val openCountLast30Days: Float,         // normalized 0–1 (capped at 60)
    val avgDailyUsageMinutes: Float,        // normalized 0–1 (capped at 60 min)
    val wasInRecentSequence: Float,         // 0 or 1 — was opened as part of a chain recently

    // Calendar signals
    val hasUpcomingEventIn30Min: Float,     // 0 or 1
    val hasUpcomingEventIn2Hours: Float,    // 0 or 1
    val calendarEventRelatedToApp: Float,   // 0 or 1 (e.g., Teams if Teams meeting upcoming)

    // Venue affinity prior
    val venueAffinityPrior: Float           // from VenueAffinityMap, 0.0–1.0
)
// Total feature size: ~40 floats per app
```

#### Nightly Training Job (runs on WorkManager, charging + idle required)

```kotlin
class NightlyModelTrainer(
    private val dao: UsageEventDao,
    private val modelPath: String
) {

    suspend fun retrain() {
        val events = dao.getRecentEvents(days = 30)

        if (events.size < 200) {
            // Not enough data yet — skip training, rely on cold start priors
            return
        }

        val (features, labels) = buildTrainingData(events)

        // Use LiteRT Model Maker (Python, runs off-device) for initial model
        // For on-device fine-tuning, use LiteRT's transfer learning API
        // Save updated model to app's private files directory
        updateModelFile(features, labels, modelPath)
    }

    private fun buildTrainingData(
        events: List<AppUsageEvent>
    ): Pair<Array<FloatArray>, FloatArray> {
        // For each MOVE_TO_FOREGROUND event:
        //   features = context snapshot 5 minutes BEFORE the event
        //   label = 1.0 if this app was opened, 0.0 otherwise
        // Build one training example per app per context window
        TODO("Implement feature extraction from historical events")
    }
}
```

- PredictionBlender.blendScores() has a `learnedScore` parameter (defaults to 0.0)
- AriaContext's 40+ fields map to the feature vector
- NightlyPredictionWorker can host future training step
- LiteRT replaces TFLite and is 16KB-aligned — add `com.google.ai.edge.litert:litert` dep in Session 12
- LiteRT-LM (on-device LLM inference) is a separate package — covered in Session 15

---

## Session Milestones

| Session | Focus | Status |
|---------|-------|--------|
| 1 | Fork + baseline setup | ✓ |
| 2 | UsageStats + context signals | ✓ |
| 3 | Context signal refinement | ✓ |
| 4 | Nightly WorkManager job | ✓ |
| 5 | Compose home screen + skills | ✓ |
| 6 | LLM providers + chat + onboarding | ✓ |
| 7 | AriaContext + BriefItem + MCP seams | |
| 8 | BriefDataSources + Brief UI | |
| 9 | LLM editorial + SSID classification | |
| 10 | Rule engine (data model + compiler + evaluator) | |
| 11 | Chat rule routing + rules UI | |
| 12 | App chain detection + venue refinement | |
| 13 | Onboarding + settings polish | |
| 14 | ContextBar + weather + final polish | |

### Dependency Graph

```
Session 7: AriaContext + BriefItem + MCP Seams
    ↓
Session 8: BriefDataSources + Brief UI
    ↓
Session 9: LLM Editorial + SSID Classification
    ↓              ↘
Session 10: Rules    Session 12: App Chains (parallel)
    ↓
Session 11: Chat Rules + Rules UI
    ↓
Session 13: Onboarding + Settings
    ↓
Session 14: ContextBar + Weather + Polish
```

---

## Phase 1 — Foundation ✓ (Sessions 1-4)

### Done
- Forked Lawnchair `16-dev` (rebased from `14-dev` which was abandoned)
- applicationId changed to `com.aria.launcher` (github) / `com.aria.launcher.play` (play)
- ARIA package structure created under `lawnchair/src/com/aria/launcher/aria/`
- Dependencies added to `libs.versions.toml`: WorkManager, hilt-work, play-services-location
- Hilt plugin added, `@HiltAndroidApp` on LawnchairApp, `Configuration.Provider` for WorkManager
- Permissions added: ACTIVITY_RECOGNITION, ACCESS_FINE_LOCATION, ACCESS_WIFI_STATE, READ_CALENDAR
- `UsageDataRepository.kt` — AppUsageEvent + AppPrediction entities + DAOs + repository
- `AriaDatabase.kt` — Room singleton (v5)
- `AriaDataModule.kt` — Hilt @Module providing DB, DAOs, repository, UsageStatsManager
- `ContextSignalManager.kt` — charging, WiFi SSID, activity recognition, Android Auto, nearby SSIDs
- `UsageStatsCollector.kt` — reads UsageStatsManager, writes AppUsageEvent rows
- `ChargingReceiver.kt` + `ActivityUpdateReceiver.kt` — BroadcastReceivers
- `CalendarEventProvider.kt` — queries CalendarContract for upcoming events
- `ContextKey.kt` — DayType, TimeBucket, LocationHint, VehicleContext
- `PredictionEngine.kt` — Tier 1 frequency-based scoring by ContextKey
- `UsageCollectionWorker.kt` — periodic (4h) + one-time trigger
- `NightlyPredictionWorker.kt` — daily ~3AM, requires charging
- Data pipeline verified: 561 usage events on first run

---

## Phase 2 — Prediction Engine

### Two-Tier System
- **Tier 1 (rule-based):** Fast heuristics from day one — time-of-day, day-of-week, calendar lookups

---

## Phase 3 — LLM Provider Layer ✓ (Sessions 5-6)

### Files
- `llm/ChatMessage.kt` — Role, ChatMessage, ToolDefinition, ToolCall, LlmResult
- `llm/LlmProvider.kt` — Interface: complete(), streamComplete(), completeWithTools()
- `llm/ClaudeProvider.kt` — Anthropic Messages API, SSE streaming, OAuth + API key
- `llm/GeminiProvider.kt` — Native Gemini API (free tier)
- `llm/OllamaProvider.kt` — Remote server, NDJSON streaming
- `llm/OpenAICompatibleProvider.kt` — Generic chat completions (OpenRouter, OpenAI, etc.)
- `llm/LlmModule.kt` — Hilt module: OkHttpClient, Json, LlmProviderManager
- `llm/LlmProviderManager.kt` — Runtime provider selection persisted to DataStore
- `llm/AriaPrompts.kt` — System prompt builder + tool definitions

### Skill System
- `data/SkillModels.kt` — AppSkill + SkillResult + SkillAction entities
- `data/SkillDao.kt` — Skill/result queries
- `data/AriaNotificationListener.kt` — NotificationListenerService for notification data
- `data/BuiltInSkills.kt` — 9 default skills (gmail, calendar, spotify, maps, messages, weather, venue)
- `engine/SkillExecutor.kt` — Interface + SkillExecutorRegistry
- `engine/SkillOrchestrator.kt` — Context matching, freshness checks, execution
- `engine/skills/` — CalendarSkillExecutor, NotificationSkillExecutor, VenueSkillExecutor, WeatherSkillExecutor

### Chat System
- `chat/ChatState.kt` — Conversation manager with tool call loop (up to 5 rounds)
- `chat/ToolExecutor.kt` — Translates tool calls → Android actions
- `chat/composables/ChatSheet.kt` — Bottom sheet UI
- `chat/composables/ChatBubble.kt` — Message bubbles with tool result chips

### Home Screen UI
- `ui/AriaHomeState.kt` — Predicted apps, greeting, skill results
- `ui/composables/AriaBar.kt` — Greeting + date
- `ui/composables/CardFeed.kt` — Chat pill, skill cards, predicted apps
- `ui/composables/SkillCard.kt` — Individual skill result cards
- `ui/composables/PredictedAppsRow.kt` — App icon row/grid
- `ui/ThumbZoneLayout.kt` — Thumb-friendly grid reordering
- `ui/CardFeedState.kt` — SkillResult → CardFeedItem conversion

### Onboarding & Settings
- `ui/onboarding/` — 6-page wizard (welcome, permissions, notification, AI setup, WiFi, ready)
- `data/AriaPreferences.kt` — DataStore preferences
- `data/NearbyWifiScanner.kt` — WiFi scan + VenuePatterns matching
- `data/AndroidAutoReceiver.kt` — Android Auto connection detection
- `data/UserMemory.kt` — User memory extraction from chat

---

## Session 7: AriaContext + BriefItem Foundation + MCP Seams

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

## Session 8: BriefDataSources + Brief UI Composables

**Goal**: The home screen visually transforms. Concrete BriefDataSource implementations + all card composables.

**New files**:
- `ui/brief/sources/CalendarBriefSource.kt` — Events within 2h → BriefItem.CalendarEvent
- `ui/brief/sources/NotificationBriefSource.kt` — Synthesized alerts → AlertAssessed/ReminderNudge
- `ui/brief/sources/MediaBriefSource.kt` — Media notifications → MediaResume
- `ui/brief/sources/VenueBriefSource.kt` — Nearby WiFi → VenueCard
- `ui/brief/sources/SkillBridgeSource.kt` — Wraps existing SkillOrchestrator output → BriefItem
- `ui/brief/composables/BriefCard.kt` — Shared container: 16dp corners, 85% opacity, 2dp elevation
- `ui/brief/composables/AlertAssessedCard.kt`
- `ui/brief/composables/ReminderNudgeCard.kt`
- `ui/brief/composables/CalendarEventCard.kt`
- `ui/brief/composables/MediaResumeCard.kt`
- `ui/brief/composables/ProactiveSuggestionCard.kt`
- `ui/brief/composables/VenueCardComposable.kt`
- `ui/brief/composables/AriaBrief.kt` — LazyColumn with AnimatedVisibility, when(item) dispatch
- `ui/brief/BriefModule.kt` — Hilt: provides List<BriefDataSource>, BriefAggregator

**Modified**:
- `ui/AriaHomeState.kt` — Inject BriefAggregator + AriaContextMonitor, add `briefItems: StateFlow<List<BriefItem>>`, wire contextChanges → refreshBrief()
- `ui/composables/AriaBar.kt` — Accept optional ContextBar
- `ui/AriaPredictedPanel.kt` / `ui/composables/CardFeed.kt` — Render AriaBrief instead of SkillCards

---

## Session 9: LLM Editorial Engine + SSID Venue Classification

**Goal**: LLM curates the Brief (structured JSON output). Cold-start venue intelligence begins.

**New files**:
- `engine/BriefEditorialEngine.kt` — Calls LLM once per context change, receives JSON → List<BriefItem>. Falls back to heuristic if no LLM configured.
- `llm/EditorialPrompts.kt` — Editorial system prompt: context snapshot → JSON, max 5 items, judgment headlines
- `data/SsidClassification.kt` — Room entity: ssidHash, rawSsid, venueCategory, visitContext, visitCount, avgDuration, firstSeen, lastSeen
- `data/SsidClassificationDao.kt`
- `engine/SsidPatternMatcher.kt` — Regex-based Layer 1 (instant, no LLM)
- `engine/SsidClassificationService.kt` — 3-layer stack: pattern match → LLM (once) → cached forever
- `engine/VenueAffinityMap.kt` — Default app affinities per VenueCategory, filtered by installed packages
- `engine/VisitContextInference.kt` — inferVisitContext() heuristics
- `data/VenueCategory.kt` — 12-value enum

**Modified**:
- `data/AriaDatabase.kt` — v6: add SsidClassification entity
- `engine/AriaContext.kt` — Add currentVenueCategory, visitContext fields
- `engine/AriaContextMonitor.kt` — Trigger SSID classification on WiFi change
- `ui/brief/BriefAggregator.kt` — Use BriefEditorialEngine when LLM available

---

## Session 10: Rule Engine (Data Model + Compiler + Evaluator)

**Goal**: Users define natural language rules. LLM compiles once → pure Kotlin evaluation at runtime.

**New files**:
- `engine/rules/AriaRule.kt` — Room entity with RuleTrigger/RuleAction
- `engine/rules/RuleTrigger.kt` — Sealed class: WifiSsid, VenueCategory, Time, AppOpened, CalendarEvent, Location, AndroidAuto, Compound
- `engine/rules/AriaRuleDao.kt`
- `engine/rules/AriaRuleCompiler.kt` — LLM compiler → RuleCompilationResult sealed class
- `engine/rules/AriaRuleEvaluator.kt` — Pure Kotlin matches(trigger, context), zero LLM calls
- `engine/rules/RuleTypeConverters.kt` — Room JSON serialization for sealed classes
- `engine/rules/RuleModule.kt` — Hilt wiring

**Modified**:
- `data/AriaDatabase.kt` — v7: add AriaRule entity
- `engine/AriaContextMonitor.kt` — Call evaluator, attach firedRules to context
- `ui/AriaHomeState.kt` — Apply SurfaceApp/SuppressApp to predicted apps, ShowCard → ProactiveSuggestion

---

## Session 11: Chat Rule Routing + Rule Management UI

**Goal**: Connect rule engine to chat. Add rule management in settings.

**New files**:
- `chat/AriaChatHandler.kt` — Rule intent detection, routes to compiler vs general chat
- `ui/rules/RulesScreen.kt` — Compose screen: all rules with toggle/delete/trigger count
- `ui/rules/RuleCard.kt`, `ui/rules/RulesState.kt`

**Modified**:
- `chat/ChatState.kt` — Route through AriaChatHandler
- `chat/composables/` — Render rule confirmation cards, suggested reply chips
- Preferences navigation — Add rules route + settings entry

---

## Session 12: App Chain Detection + Venue Refinement

**Goal**: Enhance prediction quality. Can be done in parallel with Sessions 10-11.

**New files**:
- `engine/AppChainDetector.kt` — Finds apps opened within N minutes of each other (30-day window, min 5 occurrences)
- `data/AppChain.kt` — Room entity + DAO
- `engine/PredictionBlender.kt` — blendScores(prior, learned, observations)

**Modified**:
- `data/AriaDatabase.kt` — v8: add AppChain entity
- `scheduler/NightlyPredictionWorker.kt` — Run chain detection + update SSID visit stats
- `engine/PredictionEngine.kt` — Use PredictionBlender, inject chain boost
- `ui/AriaHomeState.kt` — Boost follow-up apps when trigger is active

---

## Session 13: Onboarding + Settings Polish

**Goal**: Ready for beta.

- Complete onboarding flow: notification listener permission, rule tutorial page
- Settings: active LLM display, WiFi labels, skill toggles, notification access, rules link
- Debug: force SSID classification, fake SSID input, Brief regeneration trigger, context dump
- QR token pairing flow for Claude OAuth

---

## Session 14: ContextBar + Weather + Final Polish

**Goal**: Production quality.

- `WeatherBriefSource` + `WeatherProvider` (Open-Meteo free API)
- `ContextBarComposable` — Always-visible weather/location line below greeting
- Brief polish: smooth transitions, swipe-to-dismiss, proper empty state (show nothing)
- Verify no animation on every unlock, only on context change

---

## What Stays Unchanged

- Data layer: UsageDataRepository, AppUsageEvent, ContextSignalManager, CalendarEventProvider, NearbyWifiScanner, AndroidAutoReceiver
- LLM layer: All providers, LlmProviderManager, ChatMessage models
- Skill system: SkillOrchestrator, SkillExecutor (bridged to Brief via SkillBridgeSource)
- Chat tool loop: ChatState (AriaChatHandler wraps it, not replaces it)
- PredictionEngine Tier 1: Continues working, PredictionBlender wraps it (Session 12)
- Scheduler: NightlyPredictionWorker (gains chain detection + SSID updates)

---

## Anti-Patterns to Actively Avoid

**The notification shade problem**: Showing raw notification content as cards. ARIA is not a second notification shade. Every card must reflect synthesis.

**Two input bars**: Having both the ARIA chat bar and a Google search bar visible at once. Disable the Google search bar by default.

**Cards that never leave**: Every BriefItem with a natural end time must have expiry. Implement expiry checking.

**Animating on every unlock**: Animate when context changes. Instant load when context is the same.

**Excessive text**: 6-word headlines, 12-word subtext. Enforce on parsing side — truncate if LLM ignores.

**Explanatory empty states**: If the Brief is empty, show nothing.

---

## Gotchas

- `PACKAGE_USAGE_STATS` cannot be granted at install — direct user to Settings > Special App Access
- WiFi SSID returns null without location permission — LocationHint defaults to UNKNOWN
- ActivityRecognition returns null without permission — commute detection disabled
- Accessibility Services face Google Play policy scrutiny — document use case
- WorkManager with `setRequiresCharging(true)` defers if not charging — correct behavior
- Daytime prediction path is purely a Room read — must be < 5ms, no ML inference
- Stream LLM responses token-by-token — never make user wait for full response
- Ollama: when away from home on mobile data, fall back to Claude or Gemini
- Widget hosting requires `BIND_APPWIDGET` + system dialog — explain during onboarding

---

## Android 16 Compatibility Notes

- **Base branch**: Rebased to `16-dev` which natively targets Android 16 (compileSdk 36, targetSdk 36)
- **16KB page alignment**: `android:pageSizeCompat="enabled"` in manifest + `useLegacyPackaging = true`
- **TFLite deferred**: Not added to deps until Tier 2 engine is needed and a 16KB-aligned build is available
