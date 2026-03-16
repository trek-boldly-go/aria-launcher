package com.aria.launcher.aria.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import com.aria.launcher.aria.data.AppPrediction
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.data.UsageStatsCollector
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.PredictionEngine
import com.aria.launcher.aria.engine.SkillOrchestrator
import com.aria.launcher.aria.engine.TimeBucket
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pm: PackageManager = appContext.packageManager

    private val _contextKey = MutableStateFlow(currentContextKey())

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

    /** Call when the launcher resumes to refresh the context key. */
    fun refreshContext() {
        val key = currentContextKey()
        _contextKey.value = key
        scope.launch(Dispatchers.IO) {
            try {
                skillOrchestrator.executeMatchingSkills(key.timeBucket.name)
            } catch (e: Exception) {
                Log.w(TAG, "Skill execution failed", e)
            }
        }
    }

    fun launchApp(packageName: String) {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private var cachedHomeWifi: String? = null
    private var cachedWorkWifi: String? = null

    init {
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
            } else {
                Log.d(TAG, "Bootstrap: PACKAGE_USAGE_STATS not granted, skipping data collection")
            }
            ariaPreferences.setBootstrapDone()
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

        /** Apps that run in the background but aren't user-facing. */
        private val BACKGROUND_BLOCKLIST = setOf(
            "com.google.android.gms",                 // Google Play Services
            "com.google.android.gsf",                 // Google Services Framework
            "com.google.android.ext.services",        // Android Services Library
            "com.google.android.providers.media.module", // Media Provider
            "com.google.android.apps.wellbeing",      // Digital Wellbeing
            "com.google.android.inputmethod.latin",   // Gboard (gets foreground events from keyboard)
            "com.android.systemui",                   // System UI
            "com.android.settings",                   // Settings (rarely intentional)
            "com.android.vending",                    // Play Store (background updates)
            "com.google.android.permissioncontroller", // Permission Controller
            "com.google.android.packageinstaller",    // Package Installer
            "com.google.android.configupdater",       // Config Updater
        )
    }
}
