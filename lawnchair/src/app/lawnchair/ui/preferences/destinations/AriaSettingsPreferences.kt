// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package app.lawnchair.ui.preferences.destinations

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.navigation.AriaLlmSetup
import app.lawnchair.ui.preferences.navigation.AriaMemory
import app.lawnchair.ui.preferences.navigation.AriaRules
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.llm.LiteRtLmProvider
import com.aria.launcher.aria.llm.LiteRtModelManager
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.ModelDownloadState
import com.aria.launcher.aria.llm.OnDeviceModel
import com.aria.launcher.aria.llm.ProviderStatus
import com.aria.launcher.aria.llm.ProviderType
import com.aria.launcher.aria.llm.displayName
import com.aria.launcher.aria.llm.isLocal
import com.aria.launcher.aria.scheduler.ModelDownloadWorker
import com.aria.launcher.aria.ui.AriaHomeState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface AriaSettingsEntryPoint {
    fun ariaPreferences(): AriaPreferences
    fun llmProviderManager(): LlmProviderManager
    fun liteRtModelManager(): LiteRtModelManager
    fun liteRtLmProvider(): LiteRtLmProvider
    fun skillDao(): SkillDao
    fun usageDataRepository(): UsageDataRepository
    fun ariaHomeState(): AriaHomeState
}

@OptIn(FlowPreview::class)
@Composable
fun AriaSettingsPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context, AriaSettingsEntryPoint::class.java)
    }

    val navController = LocalNavController.current
    val prefs = entryPoint.ariaPreferences()
    val homeWifi by prefs.homeWifiSsid.collectAsState(initial = null)
    val workWifi by prefs.workWifiSsid.collectAsState(initial = null)
    val isRightHanded by prefs.isRightHanded.collectAsState(initial = true)
    val providerType by entryPoint.llmProviderManager().activeProviderType.collectAsState(initial = null)
    val providerStatus by entryPoint.llmProviderManager().providerStatus
        .collectAsState(initial = null)

    var homeWifiInput by remember(homeWifi) { mutableStateOf(homeWifi ?: "") }
    var workWifiInput by remember(workWifi) { mutableStateOf(workWifi ?: "") }

    // LLM config state
    var llmTestResult by remember { mutableStateOf<String?>(null) }
    var llmTestRunning by remember { mutableStateOf(false) }

    // Auto-save WiFi labels with 1s debounce
    LaunchedEffect(Unit) {
        snapshotFlow { homeWifiInput }
            .drop(1) // skip initial value
            .debounce(1000L)
            .collect { value ->
                withContext(Dispatchers.IO) {
                    prefs.setHomeWifiSsid(value.ifBlank { null })
                }
            }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { workWifiInput }
            .drop(1)
            .debounce(1000L)
            .collect { value ->
                withContext(Dispatchers.IO) {
                    prefs.setWorkWifiSsid(value.ifBlank { null })
                }
            }
    }

    // Permissions health check
    val hasUsageStats = remember { hasUsageStatsPermission(context) }
    val hasNotifListener = remember {
        val cn = ComponentName(context, AriaNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        flat?.contains(cn.flattenToString()) == true
    }
    val hasLocation = remember {
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val missingPermissions = remember {
        buildList {
            if (!hasUsageStats) add("Usage access")
            if (!hasNotifListener) add("Notification listener")
            if (!hasLocation) add("Location (WiFi SSID)")
        }
    }

    PreferenceLayout(
        label = "ARIA",
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        // Permissions health check — only show when something is missing
        if (missingPermissions.isNotEmpty()) {
            PreferenceGroup(heading = "Permissions") {
                Item {
                    ClickablePreference(
                        label = "Missing permissions",
                        subtitle = missingPermissions.joinToString(", "),
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                    )
                }
                Item {
                    ClickablePreference(
                        label = "Re-run bootstrap",
                        subtitle = "Re-collect usage data and regenerate predictions",
                        onClick = {
                            scope.launch {
                                entryPoint.ariaHomeState().triggerBootstrapAfterOnboarding()
                                Toast.makeText(context, "Bootstrap started", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }

        PreferenceGroup(heading = "AI Provider") {
            Item {
                ClickablePreference(
                    label = "Active provider",
                    subtitle = providerStatus?.let { s ->
                        buildString {
                            append(if (s.isLocal) "[Local] " else "[Cloud] ")
                            append(s.displayName)
                            s.modelId?.let { append(" \u00b7 $it") }
                            if (s.type == ProviderType.OLLAMA) {
                                s.serverUrl?.let { append(" \u00b7 $it") }
                            }
                        }
                    } ?: "Not configured",
                    onClick = { navController.navigate(AriaLlmSetup) },
                )
            }
            Item {
                ClickablePreference(
                    label = "Change AI provider",
                    subtitle = "Claude, Gemini, Ollama, OpenAI-compatible",
                    onClick = { navController.navigate(AriaLlmSetup) },
                )
            }
            Item {
                ClickablePreference(
                    label = "Test LLM connection",
                    subtitle = llmTestResult ?: if (llmTestRunning) "Testing\u2026" else "Tap to test",
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
                                is LlmResult.Text -> "Connected \u2014 ${result.content.take(60)}"
                                is LlmResult.ToolUse -> "Connected (tool use supported)"
                                is LlmResult.Error -> LlmProviderManager.humanizeError(result.message)
                            }
                            llmTestRunning = false
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "On-device Model") {
            val modelManager = entryPoint.liteRtModelManager()
            val liteRtProvider = entryPoint.liteRtLmProvider()
            val llmManager = entryPoint.llmProviderManager()
            val selectedModel by llmManager.selectedOnDeviceModel
                .collectAsState(initial = modelManager.selectedModel)
            val downloadState by modelManager.downloadState()
                .collectAsState(initial = if (modelManager.isModelDownloaded()) ModelDownloadState.Completed else ModelDownloadState.NotStarted)
            val engineReady = remember { liteRtProvider.isReady() }
            val hfToken by llmManager.hfToken.collectAsState(initial = null)
            var hfTokenInput by remember(hfToken) { mutableStateOf(hfToken ?: "") }
            var showSwitchConfirm by remember { mutableStateOf<OnDeviceModel?>(null) }
            val deviceRamMb = remember { LiteRtModelManager.getDeviceTotalRamMb(context) }

            // Auto-save HF token with debounce
            LaunchedEffect(Unit) {
                snapshotFlow { hfTokenInput }
                    .drop(1)
                    .debounce(1000L)
                    .collect { value ->
                        withContext(Dispatchers.IO) {
                            llmManager.setHfToken(value.ifBlank { null })
                        }
                    }
            }

            // Model picker — always show ALL models
            for (model in OnDeviceModel.entries) {
                val isSelected = model == selectedModel
                val isEligible = deviceRamMb >= model.minRamMb
                val isDownloaded = modelManager.isModelDownloaded(model)
                val subtitle = when {
                    !isEligible -> "Your device doesn\u2019t have enough RAM for this model"
                    isSelected && isDownloaded -> "Selected \u00b7 ${model.sizeDescription} \u00b7 ${model.qualityDescription}"
                    isSelected -> "Selected \u00b7 ${model.sizeDescription} \u00b7 Not yet downloaded"
                    else -> "${model.sizeDescription} \u00b7 ${model.qualityDescription}"
                }
                Item {
                    ClickablePreference(
                        label = model.displayName + if (isSelected) " \u2713" else "",
                        subtitle = subtitle,
                        onClick = {
                            if (isEligible && !isSelected) {
                                showSwitchConfirm = model
                            }
                        },
                    )
                }
            }

            // Switch confirmation dialog
            if (showSwitchConfirm != null) {
                val targetModel = showSwitchConfirm!!
                AlertDialog(
                    onDismissRequest = { showSwitchConfirm = null },
                    title = { Text("Switch model?") },
                    text = {
                        Text(
                            "This will delete the current model and download " +
                                "${targetModel.displayName} (${targetModel.sizeDescription}). Continue?",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showSwitchConfirm = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    llmManager.setSelectedOnDeviceModel(targetModel)
                                }
                                if (hfTokenInput.isNotBlank()) {
                                    ModelDownloadWorker.enqueue(
                                        context,
                                        model = targetModel,
                                        hfToken = hfTokenInput,
                                    )
                                    Toast.makeText(
                                        context,
                                        "Switched to ${targetModel.displayName} \u2014 download queued",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "Switched to ${targetModel.displayName} \u2014 enter HF token to download",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }) { Text("Switch") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSwitchConfirm = null }) { Text("Cancel") }
                    },
                )
            }

            val modelStatus = when (val state = downloadState) {
                is ModelDownloadState.NotStarted -> "Not downloaded"

                is ModelDownloadState.Queued -> "Waiting for Wi-Fi + charging\u2026"

                is ModelDownloadState.Downloading -> "Downloading\u2026 ${state.progress}%"

                is ModelDownloadState.Completed -> if (engineReady) {
                    "Ready (${selectedModel.displayName})"
                } else {
                    "Downloaded \u2014 will warm up on first use"
                }

                is ModelDownloadState.Failed -> "Download failed: ${state.message}"
            }

            Item {
                ClickablePreference(
                    label = "Status",
                    subtitle = modelStatus,
                    onClick = {},
                )
            }
            val currentDownloading = downloadState as? ModelDownloadState.Downloading
            if (currentDownloading != null) {
                Item {
                    LinearProgressIndicator(
                        progress = { currentDownloading.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
            Item {
                OutlinedTextField(
                    value = hfTokenInput,
                    onValueChange = { hfTokenInput = it },
                    label = { Text("HuggingFace token") },
                    placeholder = { Text("hf_...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = {
                        Text("Required \u2014 accept Gemma license at huggingface.co, then create a token")
                    },
                )
            }
            if (downloadState is ModelDownloadState.NotStarted) {
                Item {
                    ClickablePreference(
                        label = "Download ${selectedModel.displayName}",
                        subtitle = "${selectedModel.sizeDescription} \u00b7 Wi-Fi + charging required",
                        onClick = {
                            if (hfTokenInput.isBlank()) {
                                Toast.makeText(context, "Enter a HuggingFace token first", Toast.LENGTH_SHORT).show()
                                return@ClickablePreference
                            }
                            scope.launch {
                                withContext(Dispatchers.IO) { llmManager.setHfToken(hfTokenInput) }
                            }
                            ModelDownloadWorker.enqueue(context, model = selectedModel, hfToken = hfTokenInput)
                            Toast.makeText(
                                context,
                                "Download queued \u2014 will start on Wi-Fi + charging",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                    )
                }
                Item {
                    ClickablePreference(
                        label = "Download now",
                        subtitle = "Skip Wi-Fi/charging requirement \u2014 uses ${selectedModel.sizeDescription} of data",
                        confirmationText = "This will download ${selectedModel.sizeDescription} over your current connection " +
                            "without waiting for Wi-Fi or charging. " +
                            "This may use mobile data and drain battery.",
                        onClick = {
                            if (hfTokenInput.isBlank()) {
                                Toast.makeText(context, "Enter a HuggingFace token first", Toast.LENGTH_SHORT).show()
                                return@ClickablePreference
                            }
                            scope.launch {
                                withContext(Dispatchers.IO) { llmManager.setHfToken(hfTokenInput) }
                            }
                            ModelDownloadWorker.enqueue(
                                context,
                                model = selectedModel,
                                bypassConstraints = true,
                                hfToken = hfTokenInput,
                            )
                            Toast.makeText(
                                context,
                                "Download starting now",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
            if (downloadState is ModelDownloadState.Failed) {
                Item {
                    ClickablePreference(
                        label = "Retry download",
                        subtitle = "Tap to try downloading again",
                        onClick = {
                            if (hfTokenInput.isBlank()) {
                                Toast.makeText(context, "Enter a HuggingFace token first", Toast.LENGTH_SHORT).show()
                                return@ClickablePreference
                            }
                            scope.launch {
                                withContext(Dispatchers.IO) { llmManager.setHfToken(hfTokenInput) }
                            }
                            ModelDownloadWorker.enqueue(
                                context,
                                model = selectedModel,
                                bypassConstraints = true,
                                hfToken = hfTokenInput,
                            )
                            Toast.makeText(context, "Retrying download\u2026", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            if (downloadState is ModelDownloadState.Completed && providerType != ProviderType.LITERT) {
                Item {
                    ClickablePreference(
                        label = "Switch to on-device",
                        subtitle = "Use ${selectedModel.displayName} for all inference \u2014 fully offline",
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    entryPoint.llmProviderManager().configureProvider(
                                        type = ProviderType.LITERT,
                                    )
                                }
                                Toast.makeText(context, "Switched to on-device LLM", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            if (providerType == ProviderType.LITERT) {
                val fallbackType by entryPoint.llmProviderManager().fallbackProviderType
                    .collectAsState(initial = null)
                fallbackType?.let { fallback ->
                    Item {
                        ClickablePreference(
                            label = "Switch back to ${fallback.displayName}",
                            subtitle = "Restore previous cloud/remote provider",
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        entryPoint.llmProviderManager().restoreFallbackProvider()
                                    }
                                    Toast.makeText(
                                        context,
                                        "Switched to ${fallback.displayName}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        PreferenceGroup(heading = "WiFi Labels") {
            Item {
                OutlinedTextField(
                    value = homeWifiInput,
                    onValueChange = { homeWifiInput = it },
                    label = { Text("Home WiFi name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Item {
                OutlinedTextField(
                    value = workWifiInput,
                    onValueChange = { workWifiInput = it },
                    label = { Text("Work WiFi name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Item {
                Text(
                    text = "Saves automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        PreferenceGroup(heading = "Layout") {
            Item {
                ClickablePreference(
                    label = "Dominant hand",
                    subtitle = if (isRightHanded) "Right (default)" else "Left",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setRightHanded(!isRightHanded)
                            }
                            Toast.makeText(
                                context,
                                "Thumb zone: ${if (!isRightHanded) "right" else "left"}-handed",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Notification Access") {
            Item {
                ClickablePreference(
                    label = "Notification listener",
                    subtitle = if (hasNotifListener) "Enabled" else "Not enabled",
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
            val notifContentEnabled by prefs.notificationContentEnabled
                .collectAsState(initial = false)
            Item {
                ClickablePreference(
                    label = "Allow AI to read message content",
                    subtitle = if (!hasNotifListener) {
                        "Enable notification listener first"
                    } else if (notifContentEnabled) {
                        "ARIA can read notification bodies when relevant"
                    } else {
                        "ARIA only sees notification titles"
                    },
                    onClick = {
                        if (hasNotifListener) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    prefs.setNotificationContentEnabled(!notifContentEnabled)
                                }
                            }
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Agentic Brief") {
            val agenticEnabled by prefs.agenticBriefEnabled
                .collectAsState(initial = false)
            Item {
                ClickablePreference(
                    label = "Agentic Brief",
                    subtitle = if (agenticEnabled) {
                        "ARIA can set alarms, get directions, and more autonomously"
                    } else {
                        "Brief only curates information — no autonomous actions"
                    },
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setAgenticBriefEnabled(!agenticEnabled)
                            }
                            Toast.makeText(
                                context,
                                if (!agenticEnabled) "Agentic Brief enabled" else "Agentic Brief disabled",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Rules") {
            Item {
                ClickablePreference(
                    label = "ARIA Rules",
                    subtitle = "View and manage rules created from chat",
                    onClick = { navController.navigate(AriaRules) },
                )
            }
        }

        PreferenceGroup(heading = "Memory") {
            Item {
                ClickablePreference(
                    label = "ARIA Memory",
                    subtitle = "View and delete what ARIA has learned about you",
                    onClick = { navController.navigate(AriaMemory) },
                )
            }
            val contactsAccess by prefs.contactsAccessEnabled.collectAsState(initial = false)
            val calendarAccess by prefs.calendarAccessEnabled.collectAsState(initial = false)
            val locationAccess by prefs.locationAccessEnabled.collectAsState(initial = false)
            Item {
                ClickablePreference(
                    label = "Allow chat agent to look up contacts",
                    subtitle = if (contactsAccess) {
                        "Enabled — contact data is sent to your LLM provider"
                    } else {
                        "Disabled"
                    },
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setContactsAccessEnabled(!contactsAccess)
                            }
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Allow chat agent to read calendar",
                    subtitle = if (calendarAccess) "Enabled" else "Disabled",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setCalendarAccessEnabled(!calendarAccess)
                            }
                        }
                    },
                )
            }
            Item {
                ClickablePreference(
                    label = "Allow chat agent to use location",
                    subtitle = if (locationAccess) "Enabled" else "Disabled",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setLocationAccessEnabled(!locationAccess)
                            }
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Editorial Prompt") {
            val editorialTemplate by prefs.editorialPromptTemplate
                .collectAsState(initial = AriaPreferences.DEFAULT_EDITORIAL_PROMPT)
            var templateInput by remember(editorialTemplate) {
                mutableStateOf(editorialTemplate)
            }

            // Auto-save with 2s debounce
            LaunchedEffect(Unit) {
                snapshotFlow { templateInput }
                    .drop(1)
                    .debounce(2000L)
                    .collect { value ->
                        withContext(Dispatchers.IO) {
                            prefs.setEditorialPromptTemplate(value)
                        }
                    }
            }

            Item {
                OutlinedTextField(
                    value = templateInput,
                    onValueChange = { templateInput = it },
                    label = { Text("System prompt template") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    maxLines = 50,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
            Item {
                Text(
                    text = "Variables: \${time}, \${time_bucket}, \${day_type}, \${location}, " +
                        "\${activity}, \${charging}, \${vehicle}, \${weather}, \${calendar}, " +
                        "\${recent_apps}, \${recent_packages}, \${venue}, \${rules}, " +
                        "\${visit_context}, \${capabilities}, \${notifications}, " +
                        "\${battery}, \${typical_apps}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Item {
                ClickablePreference(
                    label = "Reset to default",
                    subtitle = "Restore the built-in editorial prompt",
                    confirmationText = "This will replace your custom prompt with the default. Continue?",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setEditorialPromptTemplate(AriaPreferences.DEFAULT_EDITORIAL_PROMPT)
                            }
                            Toast.makeText(context, "Prompt reset to default", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }

        PreferenceGroup(heading = "Skills") {
            Item {
                var skillCount by remember { mutableStateOf<Int?>(null) }
                if (skillCount == null) {
                    scope.launch {
                        val dao = entryPoint.skillDao()
                        skillCount = withContext(Dispatchers.IO) { dao.getAllSkills().size }
                    }
                }
                ClickablePreference(
                    label = "Registered skills",
                    subtitle = "${skillCount ?: "…"} skills registered",
                    onClick = {},
                )
            }
        }

        PreferenceGroup(heading = "Data") {
            Item {
                ClickablePreference(
                    label = "Clear ARIA data",
                    subtitle = "Delete all usage data, predictions, and skill results",
                    confirmationText = "This will delete all collected data. ARIA will need to re-learn your patterns.",
                    onClick = {
                        scope.launch {
                            val repo = entryPoint.usageDataRepository()
                            val dao = entryPoint.skillDao()
                            withContext(Dispatchers.IO) {
                                repo.pruneOldEvents(retentionDays = 0)
                                repo.clearAllPredictions()
                                dao.deleteAllResults()
                            }
                            Toast.makeText(context, "All ARIA data cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
