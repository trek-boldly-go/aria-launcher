// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package app.lawnchair.ui.preferences.destinations

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.navigation.AriaRules
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.llm.LiteRtLmProvider
import com.aria.launcher.aria.llm.LiteRtModelManager
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.llm.LlmResult
import com.aria.launcher.aria.llm.ProviderType
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

    var homeWifiInput by remember(homeWifi) { mutableStateOf(homeWifi ?: "") }
    var workWifiInput by remember(workWifi) { mutableStateOf(workWifi ?: "") }

    // LLM config state
    var apiKeyInput by remember { mutableStateOf("") }
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
                    subtitle = llmTestResult ?: if (llmTestRunning) "Testing…" else "Tap to test",
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

        PreferenceGroup(heading = "On-device Model") {
            val modelManager = entryPoint.liteRtModelManager()
            val liteRtProvider = entryPoint.liteRtLmProvider()
            val modelDownloaded = remember { modelManager.isModelDownloaded() }
            val engineReady = remember { liteRtProvider.isReady() }

            val modelStatus = when {
                engineReady -> "Ready (Gemma3-1B)"
                modelDownloaded -> "Downloaded \u2014 warming up at next charge"
                else -> "Not downloaded"
            }

            Item {
                ClickablePreference(
                    label = "On-device LLM",
                    subtitle = modelStatus,
                    onClick = {},
                )
            }
            if (!modelDownloaded) {
                Item {
                    ClickablePreference(
                        label = "Download on-device model",
                        subtitle = "~1 GB \u00b7 Wi-Fi + charging required \u00b7 No internet needed after download",
                        onClick = {
                            ModelDownloadWorker.enqueue(context)
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
                        subtitle = "Skip Wi-Fi/charging requirement \u2014 uses ~1 GB of data",
                        confirmationText = "This will download ~1 GB over your current connection " +
                            "without waiting for Wi-Fi or charging. " +
                            "This may use mobile data and drain battery.",
                        onClick = {
                            ModelDownloadWorker.enqueue(context, bypassConstraints = true)
                            Toast.makeText(
                                context,
                                "Download starting now",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
            }
            if (modelDownloaded && providerType != ProviderType.LITERT) {
                Item {
                    ClickablePreference(
                        label = "Switch to on-device",
                        subtitle = "Use Gemma3-1B for all inference \u2014 fully offline",
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
