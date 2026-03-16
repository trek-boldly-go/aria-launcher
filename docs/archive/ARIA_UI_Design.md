# ARIA UI Design Philosophy & Component System
## Supplement to ARIA_project_plan.md — For Claude Code

> This document must be read before implementing Phase 3 (Home Screen UI).
> It defines the foundational design philosophy, the structured output system that bridges the LLM and the UI, and the component vocabulary ARIA uses.
> Deviating from this philosophy — even with good intentions — risks producing a launcher that feels like "AI bolted onto Android" rather than something genuinely new.

---

## The Core Philosophy: Temporal UI, Not Spatial UI

### What every other launcher does (spatial)

Traditional launchers — including Lawnchair's default behavior — are **spatial**.
Apps live in fixed grid positions. Folders live in fixed positions. The user
navigates to things. The phone is a map and the user travels through it.

This made sense when the phone was dumb and the user was the intelligence.

### What ARIA does (temporal)

ARIA is **temporal**. It knows what moment the user is in — the time, the
location, the context, the calendar, the recent behavior — and presents what
is relevant to *right now*. The user does not navigate. Things arrive.

The home screen is not a place the user goes. It is a **surface that changes
around the user**.

### What this means in practice

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

---

## The Brief: ARIA's Primary Output Surface

The home screen's primary content area is called **the Brief**. It is a scrollable list of BriefItem cards, generated fresh on each context change (not on every unlock — only when the context bucket actually changes).

### Properties of the Brief

- **Maximum 5 items.** If ARIA cannot decide what is important enough to show in 5 items, it is not doing its job. Ruthless curation is the feature.
- **Each item reflects judgment, not raw data.** ARIA has already thought about the item before showing it. It does not forward notifications. It synthesizes.
- **Items appear and disappear.** A meeting card appears 15 minutes before a meeting and is gone 30 minutes after it starts. A venue card appears near a known SSID and disappears when the network changes. Nothing is permanent.
- **The Brief is not a notification shade.** Android already has one. ARIA's Brief contains only things ARIA has decided are worth the user's attention, framed in ARIA's voice.

---

## Structured Output: How the LLM Talks to the UI

The LLM never writes layout code. The LLM never produces markdown that gets rendered directly. The LLM produces a **JSON payload** that ARIA's native Compose components know how to render.

This solves the wall-of-text problem entirely. The LLM makes editorial decisions (what to show, in what order, how to frame it). Android makes all rendering decisions.

### The LLM's output format

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

## BriefItem Type Vocabulary

This is the full component vocabulary. Every card on the home screen is one of these types. New types are added deliberately, not on demand.

### Sealed Class Definition

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

---

## Compose Implementation Structure

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

---

## Context Change vs. Unlock

**ARIA does not regenerate the Brief on every unlock.**

Regenerating on every unlock would mean an LLM call on every unlock — expensive,
slow, and unnecessary. Instead:

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

---

## The Greeting

The greeting is not decorative. It acknowledges the user's current moment.
It should feel like a smart assistant who knows what is going on.

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

---

## Predicted Apps Row

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

---

## LLM Editorial System Prompt

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

## Anti-Patterns to Actively Avoid

**The notification shade problem**: Showing raw notification content as cards.
ARIA is not a second notification shade. Every card must reflect synthesis.

**Two input bars**: Having both the ARIA chat bar and a Google search bar
visible at once. Disable the Google search bar by default in Lawnchair settings.

**Cards that never leave**: Every BriefItem that has a natural end time must
have an `expiresAt` timestamp. Implement expiry checking in the ViewModel.

**Animating on every unlock**: Animate when context changes. Instant load when
context is the same. Animating every unlock feels unstable.

**Excessive text**: The system prompt enforces 6-word headlines and 12-word
subtext. Also enforce this on the parsing side — truncate if the LLM ignores
the instruction. Do not let long LLM output leak into the UI.

**Explanatory empty states**: If the Brief is empty, show nothing — not a card
that says "Nothing to show right now." Empty is correct behavior. Explaining
it is clutter.

---

## Integration With Other ARIA Documents

- `BriefDataSource` interface from `ARIA_mcp_layer.md` feeds `BriefAggregator`
- `BriefItem.LiveDataCard` is defined now, populated in Phase 8
- `AriaRuleEvaluator` output feeds the LLM prompt as `firedRules`
- `VenueAffinityMap` results feed the predicted apps row, not the Brief directly
- The `ActionDispatcher` from `ARIA_mcp_layer.md` handles `BriefAction` execution
