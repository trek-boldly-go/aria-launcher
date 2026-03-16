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

```kotlin
sealed class BriefItem {
    data class AlertAssessed(...)      // An alert ARIA has already assessed — includes verdict
    data class ReminderNudge(...)      // Time-sensitive nudge the user might want to act on
    data class CalendarEvent(...)      // Calendar event coming up soon
    data class MediaResume(...)        // Media the user was consuming and can resume
    data class LiveDataCard(...)       // Live data from an MCP source (Phase 8+)
    data class ProactiveSuggestion(...) // Pattern-based proactive suggestion
    data class VenueCard(...)          // Venue-aware card near a known location
    data class ContextBar(...)         // Compact weather + context line (greeting area, not Brief)
}
```

### Structured Output: How the LLM Talks to the UI

The LLM never writes layout code. It produces a **JSON payload** that Compose components render. The `type` field maps to a specific Composable. The LLM chooses from a fixed vocabulary of types.

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
- **No Google search bar.** The ARIA chat bar IS the search.
- **No static widgets.** Widgets are replaced by BriefItems. Traditional widget placement remains opt-in.
- **No folder grid on the home screen.** Folders live in the app drawer.

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

ARIA is an MCP client that connects to servers wherever they live — home servers (on WiFi), cloud APIs, and on-device processes. MCP gives ARIA the ability to take actions inside apps and fetch live data without the user opening anything.

### Four MCP Seams (defined early, implemented later)

1. **ActionExecutor interface** — Shared interface for Intent, Accessibility, and future MCP executors. ActionDispatcher tries executors in priority order.
2. **BriefDataSource interface** — Pluggable data sources for Brief generation. MCP servers become data sources in Phase 8.
3. **RuleAction.FetchData** — Rule action variant that triggers MCP tool calls. No-op until MCP executor exists.
4. **AriaSkill interface** — Named automation sequences with required servers/permissions. Skills registry populated from community contributions.

### Data Models (defined, not persisted until needed)

```kotlin
data class McpServer(id, serverName, endpoint, transportType, isReachable, ...)
data class McpCapability(id, serverId, toolName, description, inputSchemaJson, ...)
data class McpExecution(id, serverId, toolName, inputJson, outputJson, triggeredBy, ...)
```

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

### Rule Engine

Users tell ARIA rules in plain English via chat. The LLM compiles the natural language rule into a structured `AriaRule` once. At runtime, rule evaluation is pure Kotlin — no LLM involved.

```
User: "Always show me the McDonald's app when I'm on McDonald's wifi"
  → LLM compiles once → AriaRule(trigger=WifiSsid, action=SurfaceApp)
  → stored in Room → evaluated at runtime with zero LLM calls
```

### App Chain Detection

Finds apps consistently opened within N minutes of each other. When the trigger app is active, boost the follow-up app's prediction score.

### TFLite Accommodation

- PredictionBlender.blendScores() has a `learnedScore` parameter (defaults to 0.0)
- AriaContext's 40+ fields map to the feature vector
- NightlyPredictionWorker can host future training step
- No TFLite dependency added until 16KB-aligned build available

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
- **Tier 2 (TFLite):** Deferred until 16KB-aligned build available

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
