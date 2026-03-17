# Session 9: LLM Editorial Engine + SSID Venue Classification

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

## Session 9 Deliverables

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

## Spec: LLM Editorial System Prompt

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

## Spec: Three-Layer Venue Intelligence Stack

```
Layer 1: SSID Pattern Matching (instant, no ML, no LLM)
         ↓ if unrecognized
Layer 2: LLM SSID Classification (runs ONCE per novel SSID, cached forever)
         ↓
Layer 3: Venue Affinity Map (default app suggestions by venue category)
         ↓ yields to →
LiteRT Model (overrides defaults once personal behavior is observed)
```

### Data Models

```kotlin
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
    val userConfirmed: Boolean = false
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

### Layer 1: SSID Pattern Matching

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

### Layer 2: LLM SSID Classification (runs once, cached forever)

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

### Layer 3: Venue Affinity Map

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

### Visit Context Inference

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

### How Cold Start Yields to LiteRT

The affinity map provides a `prior score`. LiteRT's output is a `learned score`. Final prediction score blends both, weighted by how much personal data has been collected at that venue:

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
