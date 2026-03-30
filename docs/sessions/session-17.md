# Session 17: Context Enrichment, Dynamic Tools & Editable Editorial Prompt

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

## Session 17 Deliverables

**Goal**: Transform ARIA from a predicted apps row with duplicate weather into a context-aware personal assistant. Enrich the LLM's context with device capabilities, notifications, battery, and usage patterns. Make the editorial prompt user-editable. Add dynamic intent-backed chat tools based on installed apps.

**Status**: All deliverables complete. Build passes. Spotless passes.

---

### Part 1: User-Editable Editorial Prompt Template

The key architectural change: instead of a hardcoded editorial prompt in `EditorialPrompts.kt`, we built a **template system with `${variable}` injection**. The prompt template is stored in DataStore and editable in Settings. Code fills in live values at runtime.

- [x] **`AriaPreferences.kt`** — Added `editorialPromptTemplate` Flow, `getEditorialPromptTemplate()`, `setEditorialPromptTemplate()`, `KEY_EDITORIAL_PROMPT` preference key, and `DEFAULT_EDITORIAL_PROMPT` constant with all `${variable}` placeholders
- [x] **`EditorialPrompts.kt`** — Complete rewrite: `resolveTemplate(template, variables)` replaces `${variable}` placeholders with live values; `buildVariables(context, weather, capabilities, notifications, battery, typicalApps)` builds the variable map from all context sources
- [x] **`BriefEditorialEngine.kt`** — Reads template from preferences, builds variables with capabilities/notifications/typicalApps, resolves template. Added weather card safety-net filter (drops `LiveDataCard` items with weather-related icons). Added `buildNotificationSummary()` and `buildTypicalAppsSummary()`.
- [x] **`AriaSettingsPreferences.kt`** — New "Editorial Prompt" `PreferenceGroup` with multiline `OutlinedTextField` (240dp, max 50 lines), 2s debounced auto-save, "Reset to default" button with confirmation dialog, helper text listing all available `${variables}`
- [x] **`AriaPredictedPanel.kt`** — Wrapped `AriaBrief` + spacer in `if (briefItems.isNotEmpty())` conditional; reduced spacers (top 8dp→4dp, ChatPill-Brief 12dp→8dp, Brief-Apps 16dp→12dp)

#### Template Variables

| Variable | Source | Example |
|---|---|---|
| `${time}` | System clock | "9:32 AM" |
| `${time_bucket}` | ContextKey | "MORNING" |
| `${day_type}` | ContextKey | "WEEKDAY" |
| `${location}` | ContextKey | "HOME" |
| `${activity}` | ContextSignalManager | "still" |
| `${charging}` | ContextSignalManager | "yes" |
| `${vehicle}` | ContextKey + Android Auto | "no" |
| `${weather}` | WeatherProvider | "Partly cloudy, 34°F" |
| `${calendar}` | CalendarEventProvider | "Team standup (at 9:30 AM, in 20min)" |
| `${recent_apps}` | UsageDataRepository | "Gmail, Slack, Chrome" |
| `${recent_packages}` | UsageDataRepository | "package:com.google.android.gm, ..." |
| `${venue}` | SsidClassificationService | "WORK" |
| `${rules}` | RuleEvaluator | "none" |
| `${visit_context}` | SsidClassificationService | "unknown" |
| `${capabilities}` | DeviceCapabilityCatalog | "Navigation: Google Maps ..." |
| `${notifications}` | AriaNotificationListener | "Gmail (3), Slack (5 mentions)" |
| `${battery}` | ContextSignalManager | "23% (not charging)" |
| `${typical_apps}` | UsageDataRepository | "Gmail, Slack, Chrome, Calendar" |

#### Weather Card Suppression

Two-layer approach:
1. **Prompt instruction** in `DEFAULT_EDITORIAL_PROMPT`: "NEVER generate a weather card. Weather is already in the context bar above."
2. **Safety-net filter** in `BriefEditorialEngine`: drops `LiveDataCard` items with weather-related icons (`WEATHER_ICONS` set of 16 icon names) before `.take(5)`.

---

### Part 2: Device Capability Discovery

- [x] **`DeviceCapabilityCatalog.kt`** (NEW) — `@Singleton` that discovers installed app capabilities via `PackageManager.queryIntentActivities()`. Probes 12 standard Android intents:

| Intent Action | Category | Discovers |
|---|---|---|
| `ACTION_DIAL` | phone | Dialer apps |
| `ACTION_SENDTO` (sms:) | sms | SMS apps |
| `ACTION_SENDTO` (mailto:) | email | Email apps |
| `ACTION_VIEW` (geo:) | maps | Navigation apps |
| `ACTION_SET_ALARM` | alarm | Clock/alarm apps |
| `ACTION_SET_TIMER` | timer | Timer apps |
| `ACTION_INSERT` (calendar) | calendar | Calendar apps |
| `ACTION_IMAGE_CAPTURE` | camera | Camera apps |
| `ACTION_VIEW` (http:) | browser | Browser apps |
| `ACTION_SEND` (text/plain) | share | Share targets |
| `INTENT_ACTION_MUSIC_PLAYER` | music | Music apps |
| `ACTION_SETTINGS` | settings | System settings |

Data model:
```kotlin
data class AppCapability(
    val packageName: String,
    val appLabel: String,
    val capability: String,     // "Navigate to an address"
    val intentTemplate: String, // "google.navigation:q={destination}"
    val category: String,       // "navigation", "communication", "media"
)
```

- Results cached 24h in memory, invalidated on package changes
- `getCapabilitySummaryForPrompt()` returns compact grouped text for the `${capabilities}` variable
- `hasCapability(category)` for checking availability
- Thread-safe with `Mutex`

---

### Part 3: Dynamic Chat Tools

- [x] **`AriaPrompts.kt`** — Complete rewrite with dynamic tool building. `buildSystemPrompt()` now accepts `capabilitySummary` parameter. Renamed `ariaTools` to `coreTools` (5 original tools), added backward-compat `ariaTools` getter. New `buildTools(capabilities)` generates tools conditionally based on discovered device capabilities:

| Tool | Intent | Only if |
|---|---|---|
| `make_call` | `ACTION_DIAL` | phone category resolved |
| `send_email` | `ACTION_SENDTO` (mailto:) | email category resolved |
| `set_timer` | `AlarmClock.ACTION_SET_TIMER` | timer category resolved |
| `create_event` | `ACTION_INSERT` (CalendarContract) | calendar category resolved |
| `take_photo` | `ACTION_IMAGE_CAPTURE` | camera category resolved |
| `share_text` | `ACTION_SEND` with chooser | share category resolved |
| `play_music` | `INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` | music category resolved |

- [x] **`ToolExecutor.kt`** — Added 7 new handlers: `executeMakeCall`, `executeSendEmail`, `executeSetTimer`, `executeCreateEvent`, `executeTakePhoto`, `executeShareText`, `executePlayMusic`. Each constructs the appropriate Android intent.
- [x] **`ChatState.kt`** — Fetches capabilities from `DeviceCapabilityCatalog`, builds summary, passes to `buildSystemPrompt()`, uses `AriaPrompts.buildTools(capabilities)` instead of static `ariaTools`
- [x] **`ChatModule.kt`** — Added `capabilityCatalog: DeviceCapabilityCatalog` parameter to `provideChatState()`

---

### Part 4: Enriched Editorial Context

Each sub-task populates a new `${variable}` in the template system — no prompt file edits needed.

- [x] **`${notifications}`** — `BriefEditorialEngine.buildNotificationSummary()` reads from `AriaNotificationListener.getNotifications()`, groups by package, shows count + latest title per app
- [x] **`${battery}`** — `ContextSignalManager` now tracks `batteryLevel: StateFlow<Int>` via `BatteryManager.EXTRA_LEVEL/EXTRA_SCALE`. `readBatteryLevel()` called in `init()` and on charging state changes. Auto-derived in `buildVariables()` from `context.batteryLevel`.
- [x] **`${typical_apps}`** — `BriefEditorialEngine.buildTypicalAppsSummary()` uses `usageDataRepository.getTopApps(contextKey, 5)` for predicted apps in the current time bucket + day type
- [x] **`AriaContext.kt`** — Added `val batteryLevel: Int = -1` field. `bucketHash()` includes battery decile: `result = 31 * result + (batteryLevel / 10)` so Brief regenerates when battery changes meaningfully.

---

### Part 5: Heuristic Fallback for No-LLM Users

- [x] **`UsagePatternBriefSource.kt`** (NEW) — Implements `BriefDataSource` interface. Generates `ProactiveSuggestion` cards: "You usually open X now" for apps predicted but not recently opened. Uses `usageDataRepository.getTopApps()`, filters out recently used apps, takes top 2.
- [x] **`BriefModule.kt`** — Added `usagePatternBriefSource` to `provideBriefSources()`

---

## Design Decisions

### Hybrid approach: tools vs context for intents
Android intents are discovered at runtime via `PackageManager.queryIntentActivities()` — no static library needed. We use a **hybrid approach**:
- **Chat**: curated intent-backed tools (12 tools) for direct action ("call mom", "set a timer for 5 minutes")
- **Editorial prompt**: capability summary as context (`${capabilities}`) so the LLM picks real `intentUri` values for Brief card actions

### Template system for editorial prompt
Rather than editing Kotlin code every time we want to change prompt wording, the template lives in DataStore. Power users can tune it in Settings. Future context signals just add new `${variables}` — no code changes for prompt wording.

### Weather dedup: prompt + safety net
The ContextBar already shows weather. Two-layer suppression prevents the LLM from generating redundant weather cards: a prompt instruction (soft) and a code filter on weather-related icons (hard).

---

## Known Issues

- **2 pre-existing test failures** (`ClaudeProviderTest > OAuth token uses Bearer auth`, `ChatMessageTest > Role enum has expected values`) — verified these exist on clean `aria/main` before this session's changes. NOT regressions.

---

## Files Modified

| File | Change |
|------|--------|
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/AriaSettingsPreferences.kt` | +101 — Editorial prompt settings UI (text box, reset, helper text) |
| `lawnchair/src/com/aria/launcher/aria/chat/ChatModule.kt` | +3 — Wire `DeviceCapabilityCatalog` into `provideChatState()` |
| `lawnchair/src/com/aria/launcher/aria/chat/ChatState.kt` | +34 — Fetch capabilities, build summary, dynamic tools |
| `lawnchair/src/com/aria/launcher/aria/chat/ToolExecutor.kt` | +108 — 7 new intent-backed tool handlers |
| `lawnchair/src/com/aria/launcher/aria/data/AriaPreferences.kt` | +77 — Editorial prompt template (DataStore, Flow, default) |
| `lawnchair/src/com/aria/launcher/aria/data/ContextSignalManager.kt` | +14 — Battery level StateFlow |
| `lawnchair/src/com/aria/launcher/aria/engine/AriaContext.kt` | +6 — `batteryLevel` field + bucketHash decile |
| `lawnchair/src/com/aria/launcher/aria/engine/BriefEditorialEngine.kt` | +84 — Template resolution, notification/typical-apps summaries, weather filter |
| `lawnchair/src/com/aria/launcher/aria/engine/DeviceCapabilityCatalog.kt` | NEW — Device capability discovery (12 intent probes, 24h cache) |
| `lawnchair/src/com/aria/launcher/aria/llm/AriaPrompts.kt` | +184 — Dynamic tool building, capability-aware system prompt |
| `lawnchair/src/com/aria/launcher/aria/llm/AuthConfig.kt` | NEW — Auth configuration data class |
| `lawnchair/src/com/aria/launcher/aria/llm/EditorialPrompts.kt` | Rewrite — Template + variable injection system |
| `lawnchair/src/com/aria/launcher/aria/llm/LiteRtLmProvider.kt` | +1 — Minor fix |
| `lawnchair/src/com/aria/launcher/aria/llm/LlmProviderManager.kt` | +92 — Provider management updates |
| `lawnchair/src/com/aria/launcher/aria/llm/OllamaProvider.kt` | +175 — Ollama provider enhancements |
| `lawnchair/src/com/aria/launcher/aria/ui/AriaHomeState.kt` | +8 — Home state updates |
| `lawnchair/src/com/aria/launcher/aria/ui/AriaPredictedPanel.kt` | +18 — Conditional Brief rendering, reduced spacers |
| `lawnchair/src/com/aria/launcher/aria/ui/brief/BriefModule.kt` | +3 — Wire `UsagePatternBriefSource` |
| `lawnchair/src/com/aria/launcher/aria/ui/brief/sources/CalendarBriefSource.kt` | +10 — Calendar source fixes |
| `lawnchair/src/com/aria/launcher/aria/ui/brief/sources/UsagePatternBriefSource.kt` | NEW — Heuristic "You usually open X now" cards |
| `lawnchair/src/com/aria/launcher/aria/ui/onboarding/LlmSetupPage.kt` | +279 — LLM setup page enhancements |
| `tests/unit/com/aria/launcher/aria/llm/AuthConfigTest.kt` | NEW — AuthConfig unit tests |
| `tests/unit/com/aria/launcher/aria/llm/OllamaProviderTest.kt` | +187 — Ollama provider test coverage |

**Total: 19 files modified, 4 new files, +1364 / -142 lines**

---

## Dependencies

No new Gradle dependencies. All changes use existing libraries (Room, Hilt, Compose, OkHttp, DataStore).
