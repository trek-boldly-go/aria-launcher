# ARIA Intelligence Layer
## Supplement to ARIA_project_plan.md — For Claude Code
 
This document covers three systems that work together to make ARIA feel intelligent
from day one, improve over time, and learn explicit rules from the user:
 
1. Cold Start & Venue Intelligence (no prior behavior needed)
2. TFLite Prediction Model (learns from observed behavior)
3. ARIA Rule Engine (user-defined natural language rules)
 
---
 
## System 1: Cold Start & Venue Intelligence
 
### The Problem
TFLite can't predict behavior it hasn't observed. When a user arrives at a new
location, ARIA needs a reasonable prior before any personal data exists.
 
### Architecture: Three-Layer Stack
 
```
Layer 1: SSID Pattern Matching (instant, no ML, no LLM)
         ↓ if unrecognized
Layer 2: LLM SSID Classification (runs ONCE per novel SSID, result cached forever)
         ↓
Layer 3: Venue Affinity Map (default app suggestions by venue category)
         ↓ yields to →
TFLite Model (overrides defaults once personal behavior is observed)
```
 
---
 
### Data Models
 
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
 
---
 
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
 
---
 
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
 
---
 
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
            // Appointment/visitor context
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
 
---
 
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
 
---
 
### How Cold Start Yields to TFLite
 
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
 
---
 
## System 2: TFLite Prediction Model
 
### Feature Vector (per app, per prediction request)
 
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
 
### Sequential Chain Detection
 
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
 
When the trigger app is currently in the foreground or was just opened, boost
the followUp app's prediction score. This handles the "bank → calculator → bank"
pattern without needing it to be in the feature vector explicitly.
 
### Nightly Training Job (runs on WorkManager, charging + idle required)
 
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
 
        // Use TFLite Model Maker (Python, runs off-device) for initial model
        // For on-device fine-tuning, use TFLite's transfer learning API
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
 
---
 
## System 3: ARIA Rule Engine
 
### The Core Idea
 
Users can tell ARIA rules in plain English via the chat interface. The LLM compiles the natural language rule into a structured `AriaRule` object once.
At runtime, rule evaluation is pure Kotlin logic — no LLM involved.
The LLM is only called again if the user modifies the rule.
 
```
User: "Always show me the McDonald's app when I'm on McDonald's wifi"
         ↓ LLM compiles once
AriaRule(
  trigger = WifiSsidTrigger(pattern = "mcdonald", matchType = CONTAINS),
  action = SurfaceApp(packageName = "com.mcdonalds.app", priority = ALWAYS_SHOW)
)
         ↓ stored in Room
         ↓ evaluated at runtime with zero LLM calls
```
 
---
 
### Data Models
 
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
}
 
enum class MatchType { EXACT, CONTAINS, STARTS_WITH, REGEX }
enum class SurfacePriority { ALWAYS_SHOW, BOOST, PIN_TO_DOCK }
enum class LogicOperator { AND, OR }
```
 
---
 
### LLM Rule Compiler
 
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
 
---
 
### Rule Evaluator (runs at every context change — zero LLM calls)
 
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
 
---
 
### Chat Interface: Rule Creation Flow
 
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
                // Show user what ARIA understood, ask for confirmation
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
 
### Rule Management UI
 
Users should be able to see, edit, and delete their rules:
 
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
 
---
 
## Integration Points with ARIA_project_plan.md
 
- `SsidClassificationService` plugs into Phase 1 context signal collection
- `VenueAffinityMap` feeds into Phase 2 prediction engine as cold-start priors
- `blendScores()` is called inside `NightlyPredictionWorker` when computing final scores
- `AppChainDetector` runs as part of the nightly job, results stored in a `app_chains` table
- `AriaRuleEvaluator` is called in the home screen ViewModel on every context change
- `AriaChatHandler` replaces the direct LLM call in Phase 5 chat implementation
- Rule actions feed into the same `BriefItem` sealed class from the main plan
