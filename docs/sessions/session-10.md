# Session 10: Rule Engine (Data Model + Compiler + Evaluator)

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

## Session 10 Deliverables

**Goal**: Users define natural language rules. LLM compiles once → pure Kotlin evaluation at runtime.

```
User: "Always show me the McDonald's app when I'm on McDonald's wifi"
  → LLM compiles once → AriaRule(trigger=WifiSsid, action=SurfaceApp)
  → stored in Room → evaluated at runtime with zero LLM calls
```

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

## Spec: Rule Data Models

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
        val intentUri: String? = null
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
        val onFailure: RuleAction?      // optional fallback — MCP Seam 3, no-op until Phase 8
    ) : RuleAction()
}

enum class MatchType { EXACT, CONTAINS, STARTS_WITH, REGEX }
enum class SurfacePriority { ALWAYS_SHOW, BOOST, PIN_TO_DOCK }
enum class LogicOperator { AND, OR }
```

---

## Spec: LLM Rule Compiler

This is the **only** place the LLM is involved in the rule system. It runs once when the user creates or edits a rule.

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

---

## Spec: Rule Evaluator (zero LLM calls at runtime)

```kotlin
class AriaRuleEvaluator(private val dao: AriaRuleDao) {

    fun evaluate(context: AriaContext): List<RuleAction> {
        val enabledRules = dao.getEnabledRules()   // synchronous Room query
        return enabledRules
            .filter { matches(it.trigger, context) }
            .map { it.action }
            .also { matched ->
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

---

## Example Rules (test these end-to-end)

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
