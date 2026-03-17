# Session 12: App Chain Detection + Venue Refinement

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

## Session 12 Deliverables

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

## Spec: App Chain Detection

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

When the trigger app is currently in the foreground or was just opened, boost the followUp app's prediction score. This handles the "bank → calculator → bank" pattern without needing it in the feature vector explicitly.

---

## Spec: PredictionBlender

Blends cold-start prior scores (VenueAffinityMap) with learned scores (LiteRT / frequency model). Also applies chain boost:

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

- `learnedScore` defaults to 0.0 until LiteRT model is trained; add `litert` gradle dep in this session (16KB alignment blocker resolved — LiteRT replaces TFLite and is 16KB-aligned). Session 12 still ships frequency-based Tier 1 as the primary scorer.
- Chain boost: when a chain's trigger app is active, add a boost multiplier to the followUp's blended score
- NightlyPredictionWorker runs `detectChains()` and stores results; daytime path reads from Room — no inference at unlock time
