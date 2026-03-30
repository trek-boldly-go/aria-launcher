package com.aria.launcher.aria.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import com.aria.launcher.aria.data.AppChain
import com.aria.launcher.aria.data.AppChainDao
import com.aria.launcher.aria.data.AppPrediction
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.data.UsageStatsCollector
import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.engine.AriaContextMonitor
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.LocationHint
import com.aria.launcher.aria.engine.PredictionBlender
import com.aria.launcher.aria.engine.PredictionEngine
import com.aria.launcher.aria.engine.SkillOrchestrator
import com.aria.launcher.aria.engine.TimeBucket
import com.aria.launcher.aria.engine.rules.RuleAction
import com.aria.launcher.aria.engine.rules.SurfacePriority
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefAggregator
import com.aria.launcher.aria.ui.brief.BriefItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PredictedApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val score: Float,
)

/**
 * Application-scoped state holder for the ARIA home screen UI.
 * Provides predicted apps and greeting text based on the current context.
 *
 * This is a @Singleton instead of a ViewModel because Launcher extends Activity
 * (not ComponentActivity), so ViewModelStore is not available.
 */
@Singleton
class AriaHomeState @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: UsageDataRepository,
    private val contextSignalManager: ContextSignalManager,
    private val skillOrchestrator: SkillOrchestrator,
    private val ariaPreferences: AriaPreferences,
    private val usageStatsCollector: UsageStatsCollector,
    private val predictionEngine: PredictionEngine,
    private val briefAggregator: BriefAggregator,
    private val contextMonitor: AriaContextMonitor,
    private val appChainDao: AppChainDao,
    private val predictionBlender: PredictionBlender,
    private val weatherProvider: WeatherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pm: PackageManager = appContext.packageManager

    @Suppress("ktlint:standard:backing-property-naming")
    private val _contextKey = MutableStateFlow(currentContextKey())

    // Chains triggered by the last foreground app — refreshed on each context refresh
    @Suppress("ktlint:standard:backing-property-naming")
    private val _activeChains = MutableStateFlow<List<AppChain>>(emptyList())

    // Brief state — driven by AriaContextMonitor context changes
    private val _briefItems = MutableStateFlow<List<BriefItem>>(emptyList())
    val briefItems: StateFlow<List<BriefItem>> = _briefItems.asStateFlow()

    private val _contextBar = MutableStateFlow<BriefItem.ContextBar?>(null)
    val contextBar: StateFlow<BriefItem.ContextBar?> = _contextBar.asStateFlow()

    // Track dismissed item keys for the current session
    private val dismissedKeys = mutableSetOf<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    val predictedApps: StateFlow<List<PredictedApp>> = _contextKey
        .map { it.toStringKey() }
        .flatMapLatest { key ->
            val contextSpecific = repository.observePredictions(key)
            val fallback = repository.observePredictionsFallback()
            // Use context-specific predictions if available, otherwise fall back to best across all contexts
            combine(contextSpecific, fallback) { specific, all ->
                specific.ifEmpty { all }
            }
        }
        .combine(contextMonitor.contextChanges) { predictions, context ->
            // Session 10: apply SurfaceApp/SuppressApp rule actions from fired rules
            predictions.applyRuleActions(context?.firedRules ?: emptyList())
        }
        .combine(_activeChains) { predictions, chains ->
            // Session 12: boost follow-up apps for currently-active chain triggers
            predictions.applyChainBoosts(chains)
        }
        .map { predictions ->
            Log.d(TAG, "Predictions count before filter: ${predictions.size}")
            predictions.toUiModels().also {
                Log.d(TAG, "Predictions count after filter: ${it.size}, context=${_contextKey.value.toStringKey()}")
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val greeting: StateFlow<String> = _contextKey
        .map { key -> greetingForTimeBucket(key.timeBucket) }
        .stateIn(scope, SharingStarted.Eagerly, greetingForTimeBucket(_contextKey.value.timeBucket))

    val skillResults: StateFlow<List<SkillResult>> = skillOrchestrator.observeActiveResults()
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Call when the launcher resumes to refresh the context key and Brief. */
    fun refreshContext() {
        val key = currentContextKey()
        _contextKey.value = key
        scope.launch(Dispatchers.IO) {
            try {
                contextMonitor.refresh()
                skillOrchestrator.executeMatchingSkills(key.timeBucket.name)
                refreshActiveChains()
            } catch (e: Exception) {
                Log.w(TAG, "Context/skill refresh failed", e)
            }
        }
    }

    /**
     * Queries the most recent foreground app (before the launcher) and loads
     * any chains where it is the trigger, so [predictedApps] can boost follow-ups.
     */
    private suspend fun refreshActiveChains() {
        try {
            val recentMs = System.currentTimeMillis() - CHAIN_TRIGGER_WINDOW_MS
            val recentEvent = repository.getEventsForTraining(windowDays = 1)
                .filter { it.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND }
                .filter { it.packageName != appContext.packageName }
                .maxByOrNull { it.timestamp }

            val triggerPkg = recentEvent?.takeIf { it.timestamp >= recentMs }?.packageName
            _activeChains.value = if (triggerPkg != null) {
                appChainDao.getChainsByTrigger(triggerPkg).also {
                    if (it.isNotEmpty()) Log.d(TAG, "Active chain trigger: $triggerPkg → ${it.size} follow-ups")
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Chain trigger refresh failed", e)
        }
    }

    /** Rebuild the Brief from a fresh AriaContext snapshot. */
    private suspend fun refreshBrief(context: AriaContext) {
        try {
            val allItems = buildList {
                addAll(briefAggregator.buildBrief(context))
                // Session 10: convert ShowCard rule actions into ProactiveSuggestion BriefItems
                addAll(ruleActionsToCards(context))
            }
            val filtered = allItems
                .filter { it.stableKey() !in dismissedKeys }
                .distinctBy { it.stableKey() }
                .take(com.aria.launcher.aria.ui.brief.BriefAggregator.MAX_BRIEF_ITEMS)
            _briefItems.value = filtered
            _contextBar.value = buildContextBar(context)
            Log.d(TAG, "Brief refreshed: ${filtered.size} items")
        } catch (e: Exception) {
            Log.w(TAG, "Brief refresh failed", e)
        }
    }

    /** Convert ShowCard rule actions in [context.firedRules] into ProactiveSuggestion items. */
    private fun ruleActionsToCards(context: AriaContext): List<BriefItem.ProactiveSuggestion> = context.firedRules.mapNotNull { fired ->
        val action = fired.action as? RuleAction.ShowCard ?: return@mapNotNull null
        BriefItem.ProactiveSuggestion(
            headline = action.headline,
            rationale = action.subtext ?: "",
            action = BriefAction(
                label = "Open",
                intentUri = action.intentUri,
            ),
        )
    }

    /** Remove a dismissible item from the Brief for this session. */
    fun dismissItem(item: BriefItem) {
        dismissedKeys.add(item.stableKey())
        _briefItems.update { current -> current.filter { it.stableKey() != item.stableKey() } }
    }

    /** Execute a BriefAction — handles intentUri launches and MCP tool calls (Session 9+). */
    fun executeAction(action: com.aria.launcher.aria.ui.brief.BriefAction) {
        Log.d(TAG, "executeAction: label=${action.label} intentUri=${action.intentUri}")
        val uri = action.intentUri ?: run {
            Log.w(TAG, "executeAction: intentUri is null for action '${action.label}', ignoring")
            return
        }
        try {
            val intent = if (uri.startsWith("package:")) {
                val pkg = uri.removePrefix("package:")
                // Resolve virtual package aliases (weather app varies by device/OEM)
                val candidates = PACKAGE_ALIASES[pkg] ?: listOf(pkg)
                candidates.firstNotNullOfOrNull { candidate ->
                    pm.getLaunchIntentForPackage(candidate)
                } ?: run {
                    Log.w(TAG, "No launch intent for any of: $candidates")
                    return
                }
            } else {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute brief action: ${action.label}", e)
        }
    }

    private suspend fun buildContextBar(context: AriaContext): BriefItem.ContextBar {
        val locationHint = when (context.contextKey.location) {
            LocationHint.HOME -> "Home"
            LocationHint.WORK -> "Work"
            LocationHint.COMMUTE -> "Commute"
            LocationHint.UNKNOWN -> null
        }

        // Use dedicated WeatherProvider for reliable, cached weather data
        val snapshot = weatherProvider.getWeather()
        val weatherLine = snapshot?.toWeatherLine() ?: ""
        val alertCount = skillResults.value.count { it.skillId == "weather.alerts" }

        return BriefItem.ContextBar(
            weatherLine = weatherLine,
            locationHint = locationHint,
            alertCount = alertCount,
        )
    }

    fun launchApp(packageName: String) {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private var cachedHomeWifi: String? = null
    private var cachedWorkWifi: String? = null

    init {
        // Start the context monitor — debounces signals, emits on meaningful changes
        contextMonitor.start()

        // Wire context changes → Brief refresh
        scope.launch(Dispatchers.IO) {
            contextMonitor.contextChanges
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.bucketHash() == new.bucketHash() }
                .collectLatest { context -> refreshBrief(context) }
        }

        // Ensure Brief populates on first launch even if context monitor
        // already emitted before the collector was ready
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2000)
            val ctx = contextMonitor.contextChanges.value
            if (ctx != null && _briefItems.value.isEmpty()) {
                refreshBrief(ctx)
            }
        }

        scope.launch(Dispatchers.IO) {
            cachedHomeWifi = ariaPreferences.getHomeWifiSsid()
            cachedWorkWifi = ariaPreferences.getWorkWifiSsid()

            // First-launch bootstrap: collect last 7 days and run predictions immediately
            if (!ariaPreferences.isBootstrapDone()) {
                bootstrap()
            }
        }
    }

    /**
     * Called from onboarding completion to run bootstrap immediately after permissions
     * are granted, rather than waiting for the next cold start.
     */
    fun triggerBootstrapAfterOnboarding() {
        scope.launch(Dispatchers.IO) {
            cachedHomeWifi = ariaPreferences.getHomeWifiSsid()
            cachedWorkWifi = ariaPreferences.getWorkWifiSsid()
            bootstrap()
        }
    }

    /**
     * One-time bootstrap on first launch: loads the last 7 days of usage data
     * from UsageStatsManager and immediately generates predictions so the home
     * screen isn't empty for the first week.
     */
    private suspend fun bootstrap() {
        Log.d(TAG, "Running first-launch bootstrap")
        try {
            if (usageStatsCollector.hasPermission()) {
                // Collect 7 days of history
                val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L
                usageStatsCollector.collectAndStore(windowMs = sevenDaysMs)

                // Immediately generate predictions from that data
                predictionEngine.generatePredictions(
                    homeWifiSsid = cachedHomeWifi,
                    workWifiSsid = cachedWorkWifi,
                    windowDays = 7,
                )

                // Refresh the context key to pick up the new predictions
                _contextKey.value = currentContextKey()
                Log.d(TAG, "Bootstrap complete — predictions generated from 7-day history")
                ariaPreferences.setBootstrapDone()
            } else {
                Log.d(TAG, "Bootstrap: PACKAGE_USAGE_STATS not granted, will retry next launch")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed (non-fatal, will retry next launch)", e)
        }
    }

    private fun currentContextKey(): ContextKey {
        return ContextKey.current(
            wifiSsid = contextSignalManager.wifiSsid.value,
            detectedActivity = contextSignalManager.detectedActivity.value,
            homeWifiSsid = cachedHomeWifi,
            workWifiSsid = cachedWorkWifi,
            isAndroidAutoConnected = contextSignalManager.isAndroidAutoConnected.value,
        )
    }

    /**
     * Applies a chain boost to follow-up apps for the currently-active trigger.
     * The boost is proportional to the chain's observed occurrences.
     */
    private fun List<AppPrediction>.applyChainBoosts(chains: List<AppChain>): List<AppPrediction> {
        if (chains.isEmpty()) return this
        val boostByPackage = chains.associate { it.followUp to it.occurrences }
        return map { prediction ->
            val occurrences = boostByPackage[prediction.packageName]
            if (occurrences != null) {
                prediction.copy(score = predictionBlender.applyChainBoost(prediction.score, occurrences))
            } else {
                prediction
            }
        }
    }

    private fun List<AppPrediction>.applyRuleActions(
        firedRules: List<com.aria.launcher.aria.engine.FiredRule>,
    ): List<AppPrediction> {
        val suppressedPackages = firedRules
            .mapNotNull { it.action as? RuleAction.SuppressApp }
            .map { it.packageName }
            .toSet()

        val boostedPackages = firedRules
            .mapNotNull { it.action as? RuleAction.SurfaceApp }
            .associate { it.packageName to it.priority }

        return this
            .filter { it.packageName !in suppressedPackages }
            .map { prediction ->
                val boost = boostedPackages[prediction.packageName]
                if (boost != null) {
                    val boostedScore = when (boost) {
                        SurfacePriority.ALWAYS_SHOW, SurfacePriority.PIN_TO_DOCK -> Float.MAX_VALUE
                        SurfacePriority.BOOST -> prediction.score + BOOST_SCORE_DELTA
                    }
                    prediction.copy(score = boostedScore)
                } else {
                    prediction
                }
            }
    }

    private fun List<AppPrediction>.toUiModels(): List<PredictedApp> {
        return this
            .sortedByDescending { it.score }
            .distinctBy { it.packageName }
            .filter { it.packageName !in BACKGROUND_BLOCKLIST && it.packageName != appContext.packageName }
            .mapNotNull { prediction ->
                try {
                    // Only show apps that are user-launchable
                    val launchIntent = pm.getLaunchIntentForPackage(prediction.packageName)
                        ?: return@mapNotNull null
                    val appInfo = pm.getApplicationInfo(prediction.packageName, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo)
                    PredictedApp(
                        packageName = prediction.packageName,
                        label = label,
                        icon = icon,
                        score = prediction.score,
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Predicted app not installed: ${prediction.packageName}")
                    null
                }
            }
            .take(MAX_PREDICTED_APPS)
    }

    private fun greetingForTimeBucket(bucket: TimeBucket): String {
        return when (bucket) {
            TimeBucket.EARLY_MORNING -> "Good morning"
            TimeBucket.MORNING -> "Good morning"
            TimeBucket.MIDDAY -> "Good afternoon"
            TimeBucket.AFTERNOON -> "Good afternoon"
            TimeBucket.EVENING -> "Good evening"
            TimeBucket.NIGHT -> "Good night"
        }
    }

    companion object {
        private const val TAG = "ARIA.HomeState"
        private const val MAX_PREDICTED_APPS = 20

        /** Virtual package names → ordered list of real packages to try. */
        private val PACKAGE_ALIASES = mapOf(
            "weather" to listOf(
                "com.google.android.apps.weather", // Standalone Google Weather (Pixel)
                "com.google.android.googlequicksearchbox", // Google app (has weather)
                "com.samsung.android.weather", // Samsung Weather
                "com.accuweather.android", // AccuWeather
            ),
        )
        private const val BOOST_SCORE_DELTA = 1000f // rule-boosted apps float to the top
        private const val CHAIN_TRIGGER_WINDOW_MS = 5 * 60 * 1000L // 5 min: app counts as active trigger

        /** Apps that run in the background but aren't user-facing. */
        private val BACKGROUND_BLOCKLIST = setOf(
            "com.google.android.gms", // Google Play Services
            "com.google.android.gsf", // Google Services Framework
            "com.google.android.ext.services", // Android Services Library
            "com.google.android.providers.media.module", // Media Provider
            "com.google.android.apps.wellbeing", // Digital Wellbeing
            "com.google.android.inputmethod.latin", // Gboard (gets foreground events from keyboard)
            "com.android.systemui", // System UI
            "com.android.settings", // Settings (rarely intentional)
            "com.android.vending", // Play Store (background updates)
            "com.google.android.permissioncontroller", // Permission Controller
            "com.google.android.packageinstaller", // Package Installer
            "com.google.android.configupdater", // Config Updater
        )
    }
}
