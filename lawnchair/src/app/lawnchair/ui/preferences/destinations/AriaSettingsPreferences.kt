// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package app.lawnchair.ui.preferences.destinations

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.llm.LlmProviderManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
private interface AriaSettingsEntryPoint {
    fun ariaPreferences(): AriaPreferences
    fun llmProviderManager(): LlmProviderManager
    fun skillDao(): SkillDao
}

@Composable
fun AriaSettingsPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context, AriaSettingsEntryPoint::class.java)
    }

    val prefs = entryPoint.ariaPreferences()
    val homeWifi by prefs.homeWifiSsid.collectAsState(initial = null)
    val workWifi by prefs.workWifiSsid.collectAsState(initial = null)
    val isRightHanded by prefs.isRightHanded.collectAsState(initial = true)
    val providerType by entryPoint.llmProviderManager().activeProviderType.collectAsState(initial = null)

    var homeWifiInput by remember(homeWifi) { mutableStateOf(homeWifi ?: "") }
    var workWifiInput by remember(workWifi) { mutableStateOf(workWifi ?: "") }

    PreferenceLayout(
        label = "ARIA",
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = "AI Provider") {
            Item {
                ClickablePreference(
                    label = "Active provider",
                    subtitle = providerType?.name ?: "Not configured",
                    onClick = {},
                )
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
                ClickablePreference(
                    label = "Save WiFi labels",
                    subtitle = "Home: ${homeWifi ?: "not set"}, Work: ${workWifi ?: "not set"}",
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                prefs.setHomeWifiSsid(homeWifiInput.ifBlank { null })
                                prefs.setWorkWifiSsid(workWifiInput.ifBlank { null })
                            }
                            Toast.makeText(context, "WiFi labels saved", Toast.LENGTH_SHORT).show()
                        }
                    },
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
                val isEnabled = remember {
                    val cn = ComponentName(context, AriaNotificationListener::class.java)
                    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    flat?.contains(cn.flattenToString()) == true
                }
                ClickablePreference(
                    label = "Notification listener",
                    subtitle = if (isEnabled) "Enabled" else "Not enabled",
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }

        PreferenceGroup(heading = "Skills") {
            Item {
                ClickablePreference(
                    label = "Manage skills",
                    subtitle = "Toggle built-in skills on/off",
                    onClick = {
                        scope.launch {
                            val dao = entryPoint.skillDao()
                            val skills = withContext(Dispatchers.IO) { dao.getAllSkills() }
                            val summary = skills.joinToString("\n") {
                                "${if (it.enabled) "[ON]" else "[OFF]"} ${it.name}"
                            }
                            Toast.makeText(context, summary.take(200), Toast.LENGTH_LONG).show()
                        }
                    },
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
                            val dao = entryPoint.skillDao()
                            withContext(Dispatchers.IO) {
                                dao.deleteAllResults()
                            }
                            Toast.makeText(context, "ARIA data cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}
