package app.lawnchair.ui.preferences.destinations

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.data.UsageStatsCollector
import com.aria.launcher.aria.engine.ContextKey
import com.aria.launcher.aria.engine.PredictionEngine
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.ProviderType
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
    fun llmProviderManager(): LlmProviderManager
    fun skillDao(): SkillDao
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

    // LLM state
    var apiKeyInput by remember { mutableStateOf("") }
    var llmTestResult by remember { mutableStateOf<String?>(null) }
    var llmTestRunning by remember { mutableStateOf(false) }
    val providerType by entryPoint.llmProviderManager().activeProviderType
        .collectAsState(initial = null)

    // Skill state
    var skillCount by remember { mutableStateOf<Int?>(null) }

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
        PreferenceGroup(heading = "LLM Provider") {
            Item {
                ClickablePreference(
                    label = "Active provider",
                    subtitle = providerType?.name ?: "Not configured",
                    onClick = {},
                )
            }
            Item {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("API key or OAuth token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
            Item {
                ClickablePreference(
                    label = "Configure Claude",
                    subtitle = "Set API key and test connection",
                    onClick = {
                        if (apiKeyInput.isBlank()) {
                            Toast.makeText(context, "Enter an API key first", Toast.LENGTH_SHORT).show()
                            return@ClickablePreference
                        }
                        scope.launch {
                            val manager = entryPoint.llmProviderManager()
                            val type = if (apiKeyInput.startsWith("sk-ant-oat01-")) {
                                ProviderType.CLAUDE_OAUTH
                            } else {
                                ProviderType.CLAUDE_API_KEY
                            }
                            withContext(Dispatchers.IO) {
                                manager.configureProvider(
                                    type = type,
                                    apiKey = apiKeyInput,
                                )
                            }
                            Toast.makeText(context, "Claude configured ($type)", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Configure Gemini",
                    subtitle = "Set Gemini API key",
                    onClick = {
                        if (apiKeyInput.isBlank()) {
                            Toast.makeText(context, "Enter an API key first", Toast.LENGTH_SHORT).show()
                            return@ClickablePreference
                        }
                        scope.launch {
                            val manager = entryPoint.llmProviderManager()
                            withContext(Dispatchers.IO) {
                                manager.configureProvider(
                                    type = ProviderType.GEMINI,
                                    apiKey = apiKeyInput,
                                )
                            }
                            Toast.makeText(context, "Gemini configured", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Test LLM connection",
                    subtitle = llmTestResult ?: if (llmTestRunning) "Testing..." else "Tap to test",
                    onClick = {
                        if (llmTestRunning) return@ClickablePreference
                        llmTestRunning = true
                        llmTestResult = null
                        scope.launch {
                            val manager = entryPoint.llmProviderManager()
                            val result = withContext(Dispatchers.IO) {
                                manager.testConnection()
                            }
                            llmTestResult = when (result) {
                                is LlmResult.Text -> "OK: ${result.content.take(80)}"
                                is LlmResult.ToolUse -> "OK (tool use): ${result.content.take(60)}"
                                is LlmResult.Error -> "Error: ${result.message.take(80)}"
                            }
                            llmTestRunning = false
                        }
                    },
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
                                dao.insertSkills(builtInSkills())
                            }
                            skillCount = withContext(Dispatchers.IO) { dao.getAllSkills().size }
                            Toast.makeText(context, "Seeded ${skillCount} skills", Toast.LENGTH_SHORT).show()
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

private fun builtInSkills(): List<com.aria.launcher.aria.data.AppSkill> = listOf(
    com.aria.launcher.aria.data.AppSkill(
        id = "gmail.inbox_summary",
        appPackage = "com.google.android.gm",
        name = "Inbox Summary",
        description = "Shows unread email count and top messages",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "MORNING,MIDDAY",
        refreshIntervalMin = 15,
    ),
    com.aria.launcher.aria.data.AppSkill(
        id = "calendar.next_event",
        appPackage = "com.google.android.calendar",
        name = "Next Event",
        description = "Shows the next upcoming calendar event",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "MORNING,MIDDAY,AFTERNOON",
        refreshIntervalMin = 10,
    ),
    com.aria.launcher.aria.data.AppSkill(
        id = "spotify.now_playing",
        appPackage = "com.spotify.music",
        name = "Now Playing",
        description = "Shows currently playing or recently played track",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "EVENING,NIGHT",
        refreshIntervalMin = 5,
    ),
    com.aria.launcher.aria.data.AppSkill(
        id = "maps.commute_eta",
        appPackage = "com.google.android.apps.maps",
        name = "Commute ETA",
        description = "Shows estimated commute time and traffic conditions",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "EARLY_MORNING,MORNING,AFTERNOON",
        refreshIntervalMin = 15,
    ),
    com.aria.launcher.aria.data.AppSkill(
        id = "messages.unread",
        appPackage = "com.google.android.apps.messaging",
        name = "Unread Messages",
        description = "Shows unread message count and recent contacts",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "",
        refreshIntervalMin = 10,
    ),
    com.aria.launcher.aria.data.AppSkill(
        id = "weather.forecast",
        appPackage = "com.google.android.apps.weather",
        name = "Weather Forecast",
        description = "Shows current conditions and forecast",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "EARLY_MORNING,MORNING,EVENING",
        refreshIntervalMin = 30,
    ),
    com.aria.launcher.aria.data.AppSkill(
        id = "calendar.tomorrow",
        appPackage = "com.google.android.calendar",
        name = "Tomorrow's Schedule",
        description = "Shows tomorrow's calendar events",
        triggerType = "PROACTIVE",
        sourceType = "BUILT_IN",
        contextMatch = "EVENING,NIGHT",
        refreshIntervalMin = 60,
    ),
)
