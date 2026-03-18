package app.lawnchair.ui.preferences.destinations

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.data.SsidClassificationDao
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.data.UsageStatsCollector
import com.aria.launcher.aria.engine.AriaContextMonitor
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.PredictionEngine
import com.aria.launcher.aria.engine.SkillOrchestrator
import com.aria.launcher.aria.engine.SsidClassificationService
import com.aria.launcher.aria.ui.AriaHomeState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface AriaDebugEntryPoint {
    fun predictionEngine(): PredictionEngine
    fun usageDataRepository(): UsageDataRepository
    fun contextSignalManager(): ContextSignalManager
    fun usageStatsCollector(): UsageStatsCollector
    fun skillDao(): SkillDao
    fun skillOrchestrator(): SkillOrchestrator
    fun ssidClassificationService(): SsidClassificationService
    fun ssidClassificationDao(): SsidClassificationDao
    fun ariaContextMonitor(): AriaContextMonitor
    fun ariaHomeState(): AriaHomeState
}

@Composable
fun AriaDebugPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context, AriaDebugEntryPoint::class.java)
    }

    var eventCount by remember { mutableStateOf<Int?>(null) }
    var predictionCount by remember { mutableStateOf<Int?>(null) }
    var contextKeyText by remember { mutableStateOf<String?>(null) }

    // Skill state
    var skillCount by remember { mutableStateOf<Int?>(null) }
    var activeResultCount by remember { mutableStateOf<Int?>(null) }
    var notifCount by remember { mutableStateOf<Int?>(null) }

    // SSID debug state
    var fakeSsidInput by remember { mutableStateOf("") }
    var ssidClassifyResult by remember { mutableStateOf<String?>(null) }

    // Context dump state
    var contextDumpText by remember { mutableStateOf<String?>(null) }

    // Load stats on first composition
    if (eventCount == null) {
        scope.launch {
            val repo = entryPoint.usageDataRepository()
            val signals = entryPoint.contextSignalManager()
            val skillDao = entryPoint.skillDao()
            withContext(Dispatchers.IO) {
                eventCount = repo.getEventsForTraining(30).size
                predictionCount = repo.getAllPredictions().size
                skillCount = skillDao.getAllSkills().size
            }
            val key = ContextKey.current(
                wifiSsid = signals.wifiSsid.value,
                detectedActivity = signals.detectedActivity.value,
                homeWifiSsid = null,
                workWifiSsid = null,
            )
            contextKeyText = key.toStringKey()
        }
    }

    PreferenceLayout(
        label = "ARIA Debug",
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = "Notification Listener") {
            Item {
                ClickablePreference(
                    label = "Notification listener",
                    subtitle = "Notifications captured: ${notifCount ?: "?"}",
                    onClick = {
                        notifCount = AriaNotificationListener.getNotifications().size
                        Toast.makeText(context, "Active notifications: $notifCount", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Android Auto status",
                    subtitle = run {
                        val s = entryPoint.contextSignalManager()
                        "Connected: ${s.isAndroidAutoConnected.value}, Car: ${s.connectedCarName.value ?: "none"}"
                    },
                    onClick = {},
                )
            }
        }

        PreferenceGroup(heading = "Skill Registry") {
            Item {
                ClickablePreference(
                    label = "Registered skills",
                    subtitle = "Skills in DB: ${skillCount ?: "?"}",
                    onClick = {
                        scope.launch {
                            val dao = entryPoint.skillDao()
                            val skills = withContext(Dispatchers.IO) { dao.getAllSkills() }
                            skillCount = skills.size
                            val summary = if (skills.isEmpty()) {
                                "No skills registered"
                            } else {
                                skills.joinToString("\n") { "${it.id}: ${it.name} (${it.sourceType})" }
                            }
                            Toast.makeText(context, summary.take(200), Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Seed built-in skills",
                    subtitle = "Register default skill definitions",
                    onClick = {
                        scope.launch {
                            val dao = entryPoint.skillDao()
                            withContext(Dispatchers.IO) {
                                dao.insertSkills(com.aria.launcher.aria.data.BuiltInSkills.all())
                            }
                            skillCount = withContext(Dispatchers.IO) { dao.getAllSkills().size }
                            Toast.makeText(context, "Seeded $skillCount skills", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Execute skills",
                    subtitle = "Active results: ${activeResultCount ?: "?"}",
                    onClick = {
                        scope.launch {
                            val orchestrator = entryPoint.skillOrchestrator()
                            withContext(Dispatchers.IO) {
                                orchestrator.executeAllSkills()
                            }
                            val dao = entryPoint.skillDao()
                            activeResultCount = withContext(Dispatchers.IO) {
                                dao.getActiveResults().size
                            }
                            Toast.makeText(context, "Executed. Active results: $activeResultCount", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Current Context") {
            Item {
                ClickablePreference(
                    label = "Context key",
                    subtitle = contextKeyText ?: "Loading\u2026",
                    onClick = {
                        val signals = entryPoint.contextSignalManager()
                        val key = ContextKey.current(
                            wifiSsid = signals.wifiSsid.value,
                            detectedActivity = signals.detectedActivity.value,
                            homeWifiSsid = null,
                            workWifiSsid = null,
                        )
                        contextKeyText = key.toStringKey()
                        Toast.makeText(context, "Context: ${key.toStringKey()}", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Signal snapshot",
                    subtitle = run {
                        val s = entryPoint.contextSignalManager()
                        "Charging: ${s.isCharging.value}, WiFi: ${s.wifiSsid.value ?: "null"}, Activity: ${s.detectedActivity.value ?: "null"}"
                    },
                    onClick = {},
                )
            }
        }

        PreferenceGroup(heading = "SSID Classification") {
            Item {
                OutlinedTextField(
                    value = fakeSsidInput,
                    onValueChange = { fakeSsidInput = it },
                    label = { Text("Fake SSID") },
                    placeholder = { Text("e.g. Starbucks_WiFi") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Item {
                ClickablePreference(
                    label = "Override WiFi SSID",
                    subtitle = "Inject fake SSID into ContextSignalManager",
                    onClick = {
                        if (fakeSsidInput.isBlank()) {
                            Toast.makeText(context, "Enter a fake SSID first", Toast.LENGTH_SHORT).show()
                            return@ClickablePreference
                        }
                        entryPoint.contextSignalManager().debugOverrideSsid(fakeSsidInput)
                        Toast.makeText(context, "SSID overridden: $fakeSsidInput", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Force classify SSID",
                    subtitle = ssidClassifyResult ?: "Classify the fake SSID above",
                    onClick = {
                        val ssid = fakeSsidInput.ifBlank {
                            entryPoint.contextSignalManager().wifiSsid.value
                        }
                        if (ssid.isNullOrBlank()) {
                            Toast.makeText(context, "No SSID to classify (enter one or connect to WiFi)", Toast.LENGTH_SHORT).show()
                            return@ClickablePreference
                        }
                        ssidClassifyResult = "Classifying..."
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                entryPoint.ssidClassificationService().classifyIfNeeded(ssid)
                            }
                            ssidClassifyResult = "$ssid \u2192 $result"
                            Toast.makeText(context, "Classified: $ssid \u2192 $result", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Show all classifications",
                    subtitle = "List cached SSID \u2192 venue mappings",
                    onClick = {
                        scope.launch {
                            val all = withContext(Dispatchers.IO) {
                                entryPoint.ssidClassificationDao().getAll()
                            }
                            val summary = if (all.isEmpty()) {
                                "No classifications cached"
                            } else {
                                all.joinToString("\n") { "${it.rawSsid} \u2192 ${it.venueCategory}" }
                            }
                            Toast.makeText(context, summary.take(300), Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Brief") {
            Item {
                ClickablePreference(
                    label = "Force Brief regeneration",
                    subtitle = "Rebuild context and regenerate the Brief now",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                entryPoint.ariaContextMonitor().refresh()
                            }
                            entryPoint.ariaHomeState().refreshContext()
                            Toast.makeText(context, "Brief regeneration triggered", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Item {
                val briefCount by entryPoint.ariaHomeState().briefItems
                    .collectAsState()
                ClickablePreference(
                    label = "Brief items",
                    subtitle = "${briefCount.size} items in Brief",
                    onClick = {
                        val items = entryPoint.ariaHomeState().briefItems.value
                        val summary = if (items.isEmpty()) {
                            "Brief is empty"
                        } else {
                            items.joinToString("\n") { it::class.simpleName ?: "?" }
                        }
                        Toast.makeText(context, summary.take(200), Toast.LENGTH_LONG).show()
                    },
                )
            }
        }

        PreferenceGroup(heading = "Context Dump") {
            Item {
                ClickablePreference(
                    label = "Dump full context",
                    subtitle = "Snapshot all signals, venue, rules, calendar, recent apps",
                    onClick = {
                        scope.launch {
                            val ctx = entryPoint.ariaContextMonitor().contextChanges.value
                            contextDumpText = if (ctx == null) {
                                "No context built yet"
                            } else {
                                buildString {
                                    appendLine("=== AriaContext Dump ===")
                                    appendLine("Time: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(ctx.timestampMs)}")
                                    appendLine("ContextKey: ${ctx.contextKey.toStringKey()}")
                                    appendLine("Charging: ${ctx.isCharging}")
                                    appendLine("WiFi: ${ctx.wifiSsid ?: "null"}")
                                    appendLine("Activity: ${ctx.detectedActivity ?: "null"}")
                                    appendLine("AndroidAuto: ${ctx.isAndroidAutoConnected}")
                                    appendLine("Car: ${ctx.connectedCarName ?: "none"}")
                                    appendLine("Venue: ${ctx.currentVenueCategory ?: "none"}")
                                    appendLine("VisitCtx: ${ctx.visitContext ?: "none"}")
                                    appendLine("NearbySSIDs: ${ctx.nearbySSIDs.take(5)}")
                                    appendLine("Calendar: ${ctx.upcomingEvents.size} events")
                                    ctx.upcomingEvents.take(3).forEach {
                                        appendLine("  - ${it.title}")
                                    }
                                    appendLine("RecentApps: ${ctx.recentAppPackages.take(5)}")
                                    appendLine("FiredRules: ${ctx.firedRules.size}")
                                    ctx.firedRules.forEach {
                                        appendLine("  - rule#${it.ruleId}: ${it.action}")
                                    }
                                }
                            }
                        }
                    },
                )
            }
            if (contextDumpText != null) {
                Item {
                    Text(
                        text = contextDumpText!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }

        PreferenceGroup(heading = "Data Collection") {
            Item {
                ClickablePreference(
                    label = "Collect usage data now",
                    subtitle = "Events in DB: ${eventCount ?: "?"}",
                    onClick = {
                        scope.launch {
                            val collector = entryPoint.usageStatsCollector()
                            withContext(Dispatchers.IO) {
                                collector.collectAndStore()
                            }
                            val repo = entryPoint.usageDataRepository()
                            withContext(Dispatchers.IO) {
                                eventCount = repo.getEventsForTraining(30).size
                            }
                            Toast.makeText(context, "Collection complete. Events: $eventCount", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Prediction Engine") {
            Item {
                ClickablePreference(
                    label = "Run prediction scoring",
                    subtitle = "Predictions in DB: ${predictionCount ?: "?"}",
                    onClick = {
                        scope.launch {
                            val engine = entryPoint.predictionEngine()
                            withContext(Dispatchers.IO) {
                                engine.generatePredictions(
                                    homeWifiSsid = null,
                                    workWifiSsid = null,
                                )
                            }
                            val repo = entryPoint.usageDataRepository()
                            withContext(Dispatchers.IO) {
                                predictionCount = repo.getAllPredictions().size
                            }
                            Toast.makeText(context, "Scoring complete. Predictions: $predictionCount", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Collect + Score (full pipeline)",
                    subtitle = "Run both collection and scoring",
                    onClick = {
                        scope.launch {
                            val collector = entryPoint.usageStatsCollector()
                            val engine = entryPoint.predictionEngine()
                            val repo = entryPoint.usageDataRepository()
                            withContext(Dispatchers.IO) {
                                collector.collectAndStore()
                                engine.generatePredictions(
                                    homeWifiSsid = null,
                                    workWifiSsid = null,
                                )
                                eventCount = repo.getEventsForTraining(30).size
                                predictionCount = repo.getAllPredictions().size
                            }
                            Toast.makeText(context, "Pipeline complete. Events: $eventCount, Predictions: $predictionCount", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Danger Zone") {
            Item {
                ClickablePreference(
                    label = "Clear all ARIA data",
                    subtitle = "Delete all usage events and predictions",
                    confirmationText = "This will delete all collected usage data and predictions. ARIA will need to re-learn your patterns from scratch.",
                    onClick = {
                        scope.launch {
                            val repo = entryPoint.usageDataRepository()
                            val dao = entryPoint.skillDao()
                            withContext(Dispatchers.IO) {
                                repo.pruneOldEvents(retentionDays = 0)
                                repo.clearAllPredictions()
                                dao.deleteAllResults()
                            }
                            eventCount = 0
                            predictionCount = 0
                            Toast.makeText(context, "All ARIA data cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}
