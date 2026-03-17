# Session 11: Chat Rule Routing + Rule Management UI

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

## Session 11 Deliverables

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

## Spec: Chat Rule Routing

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

---

## Spec: Rule Management UI

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

---

## Spec: Example Rules (reference for testing chat routing)

```
"Always show me the McDonald's app when I'm on McDonald's wifi"
→ WifiSsidTrigger(CONTAINS "mcdonald") + SurfaceApp(com.mcdonalds.app, ALWAYS_SHOW)

"When I connect to my car, switch to commute mode and open Spotify"
→ AndroidAutoTrigger + SetSpace("Commute")

"Remind me to open the Target app whenever I'm at Target"
→ VenueCategoryTrigger(RETAIL) + ShowCard("reminder", "You're at Target", null, null)

"Every weekday morning between 8 and 9, show me my work calendar"
→ TimeTrigger(8, 9, [Mon-Fri]) + SurfaceApp(com.google.android.calendar, ALWAYS_SHOW)
```
