# Session 8: BriefDataSources + Brief UI Composables

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

## Session 8 Deliverables

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

## Spec: BriefItem Full Type Definitions

```kotlin
sealed class BriefItem {

    data class AlertAssessed(
        val icon: String,
        val headline: String,           // ARIA's verdict, not the raw alert text
        val subtext: String?,
        val severity: AlertSeverity,    // INFO, WARNING, CRITICAL
        val action: BriefAction?
    ) : BriefItem()

    data class ReminderNudge(
        val icon: String,
        val headline: String,
        val subtext: String?,
        val action: BriefAction?
    ) : BriefItem()

    data class CalendarEvent(
        val title: String,
        val timeDescription: String,    // "in 12 minutes", "at 3:00 PM"
        val location: String?,
        val primaryAction: BriefAction, // "Join" / "Navigate" / "Open"
        val secondaryAction: BriefAction?
    ) : BriefItem()

    data class MediaResume(
        val title: String,
        val subtitle: String,           // artist, show name, podcast name
        val thumbnailUri: String?,
        val resumeAction: BriefAction
    ) : BriefItem()

    data class LiveDataCard(
        val sourceId: String,
        val icon: String,
        val headline: String,
        val subtext: String?,
        val action: BriefAction?,
        val refreshedAt: Long
    ) : BriefItem()

    data class ProactiveSuggestion(
        val headline: String,
        val rationale: String,
        val action: BriefAction,
        val dismissible: Boolean = true
    ) : BriefItem()

    data class VenueCard(
        val venueName: String,
        val venueCategory: VenueCategory,
        val headline: String,
        val actions: List<BriefAction>  // max 2 actions
    ) : BriefItem()

    data class ContextBar(
        val weatherLine: String,        // "34°F · Snow · Feels like 25°"
        val locationHint: String?,      // "Home", "Work", null if unknown
        val alertCount: Int = 0
    ) : BriefItem()
}

data class BriefAction(
    val label: String,                  // button text, max 3 words
    val intentUri: String?,
    val mcpToolCall: McpToolCall? = null
)

enum class AlertSeverity { INFO, WARNING, CRITICAL }
```

---

## Spec: Compose Implementation Structure

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
        AriaGreeting(greeting = uiState.greeting, contextBar = uiState.contextBar)
        Spacer(modifier = Modifier.height(16.dp))
        AriaChatBar(onTap = onChatBarTap, placeholder = "Ask ARIA anything...")
        Spacer(modifier = Modifier.height(16.dp))
        AriaBrief(
            items = uiState.briefItems,
            onActionClick = { action -> viewModel.executeAction(action) },
            onItemDismiss = { item -> viewModel.dismissItem(item) }
        )
        Spacer(modifier = Modifier.weight(1f))
        PredictedAppsRow(apps = uiState.predictedApps, onAppClick = { pkg -> viewModel.launchApp(pkg) })
        Spacer(modifier = Modifier.height(16.dp))
        AriaDock(apps = uiState.dockApps, onAppClick = { pkg -> viewModel.launchApp(pkg) })
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items = items, key = { it.stableKey() }) { item ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                BriefItemCard(
                    item = item,
                    onActionClick = onActionClick,
                    onDismiss = if (item.isDismissible()) { { onItemDismiss(item) } } else null
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

---

## Spec: Card Visual Style

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
        Column(modifier = Modifier.padding(16.dp), content = content)
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

## Spec: Context Change vs. Unlock

**ARIA does not regenerate the Brief on every unlock.**

Regenerating on every unlock = LLM call on every unlock — expensive, slow, unnecessary.

- Brief is regenerated when the **context bucket changes** (meaningful shift in time, location, or activity)
- On unlock: reads already-computed Brief from cache and renders instantly (< 16ms, no network call)
- Brief also regenerates when:
  - A new calendar event enters the 15-minute window
  - The WiFi network changes
  - Detected activity changes significantly (STILL → IN_VEHICLE)
  - An MCP data source pushes a new item (Phase 8+)
  - User dismisses an item

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

## Spec: Predicted Apps Row

```kotlin
@Composable
fun PredictedAppsRow(apps: List<PredictedApp>, onAppClick: (String) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(items = apps.take(6), key = { it.packageName }) { app ->
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
