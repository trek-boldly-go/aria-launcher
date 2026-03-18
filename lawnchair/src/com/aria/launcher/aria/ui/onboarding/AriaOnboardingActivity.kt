// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.onboarding

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.ui.preferences.components.PermissionRow
import app.lawnchair.ui.theme.LawnchairTheme
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.ui.AriaHomeState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOTAL_ONBOARDING_PAGES = 7

class AriaOnboardingActivity : ComponentActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OnboardingEntryPoint {
        fun llmProviderManager(): LlmProviderManager
        fun ariaPreferences(): AriaPreferences
        fun ariaHomeState(): AriaHomeState
    }

    private var currentPage by mutableIntStateOf(0)
    private var usageStatsGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var activityRecognitionGranted by mutableStateOf(false)
    private var calendarGranted by mutableStateOf(false)
    private var notifListenerEnabled by mutableStateOf(false)
    private var homeWifi by mutableStateOf("")
    private var workWifi by mutableStateOf("")

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> refreshPermissionStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionStates()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            OnboardingEntryPoint::class.java,
        )

        setContent {
            LawnchairTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OnboardingWizard(
                        currentPage = currentPage,
                        onPageChange = { currentPage = it },
                        usageStatsGranted = usageStatsGranted,
                        notificationsGranted = notificationsGranted,
                        locationGranted = locationGranted,
                        activityRecognitionGranted = activityRecognitionGranted,
                        calendarGranted = calendarGranted,
                        notifListenerEnabled = notifListenerEnabled,
                        homeWifi = homeWifi,
                        workWifi = workWifi,
                        currentWifi = null,
                        onHomeWifiChanged = { homeWifi = it },
                        onWorkWifiChanged = { workWifi = it },
                        onGrantUsageStats = { openUsageAccessSettings() },
                        onGrantRuntimePermissions = { requestRuntimePermissions() },
                        onGrantNotifListener = { openNotificationListenerSettings() },
                        onFinish = { finishOnboarding(entryPoint) },
                        llmProviderManager = entryPoint.llmProviderManager(),
                        onQrScan = {
                            QrTokenScanner.scan(
                                this@AriaOnboardingActivity,
                                entryPoint.llmProviderManager(),
                                kotlinx.coroutines.CoroutineScope(Dispatchers.Main),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStates()
    }

    private fun refreshPermissionStates() {
        usageStatsGranted = hasUsageStatsPermission()
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        activityRecognitionGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
        notifListenerEnabled = isNotificationListenerEnabled()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, AriaNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (!locationGranted) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!activityRecognitionGranted) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (!calendarGranted) perms.add(Manifest.permission.READ_CALENDAR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            runtimePermissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun finishOnboarding(entryPoint: OnboardingEntryPoint) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            val prefs = entryPoint.ariaPreferences()
            withContext(Dispatchers.IO) {
                if (homeWifi.isNotBlank()) prefs.setHomeWifiSsid(homeWifi)
                if (workWifi.isNotBlank()) prefs.setWorkWifiSsid(workWifi)
                prefs.setOnboardingVersion(AriaPreferences.CURRENT_ONBOARDING_VERSION)
            }
            // Kick off bootstrap now that permissions are granted — don't make the user wait
            // for a cold restart to see predicted apps.
            entryPoint.ariaHomeState().triggerBootstrapAfterOnboarding()
        }
        // Also set legacy SharedPreferences flag for backward compat
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
        finish()
    }

    companion object {
        const val PREFS_NAME = "aria_onboarding"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"

        fun isOnboardingComplete(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ONBOARDING_COMPLETE, false)
        }

        fun launch(context: Context) {
            context.startActivity(Intent(context, AriaOnboardingActivity::class.java))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("ktlint:compose:parameter-naming")
@Composable
private fun OnboardingWizard(
    currentPage: Int,
    onPageChange: (Int) -> Unit,
    usageStatsGranted: Boolean,
    notificationsGranted: Boolean,
    locationGranted: Boolean,
    activityRecognitionGranted: Boolean,
    calendarGranted: Boolean,
    notifListenerEnabled: Boolean,
    homeWifi: String,
    workWifi: String,
    currentWifi: String?,
    onHomeWifiChanged: (String) -> Unit,
    onWorkWifiChanged: (String) -> Unit,
    onGrantUsageStats: () -> Unit,
    onGrantRuntimePermissions: () -> Unit,
    onGrantNotifListener: () -> Unit,
    onFinish: () -> Unit,
    llmProviderManager: LlmProviderManager,
    onQrScan: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Page content with animated transitions
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally { fullWidth -> direction * fullWidth / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { fullWidth -> -direction * fullWidth / 3 } + fadeOut())
            },
            label = "pageTransition",
        ) { page ->
            when (page) {
                0 -> WelcomePage()

                1 -> PermissionsPage(
                    usageStatsGranted = usageStatsGranted,
                    notificationsGranted = notificationsGranted,
                    locationGranted = locationGranted,
                    activityRecognitionGranted = activityRecognitionGranted,
                    calendarGranted = calendarGranted,
                    onGrantUsageStats = onGrantUsageStats,
                    onGrantRuntimePermissions = onGrantRuntimePermissions,
                )

                2 -> NotificationAccessPage(
                    isEnabled = notifListenerEnabled,
                    onEnable = onGrantNotifListener,
                )

                3 -> LlmSetupPage(
                    llmProviderManager = llmProviderManager,
                    onQrScanRequested = onQrScan,
                )

                4 -> WifiSetupPage(
                    homeWifi = homeWifi,
                    workWifi = workWifi,
                    currentWifi = currentWifi,
                    onHomeWifiChanged = onHomeWifiChanged,
                    onWorkWifiChanged = onWorkWifiChanged,
                )

                5 -> RuleTutorialPage()

                6 -> ReadyPage()
            }
        }

        // Navigation bar
        Column(
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            PageIndicator(pageCount = TOTAL_ONBOARDING_PAGES, currentPage = currentPage)
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentPage > 0) {
                    TextButton(
                        onClick = { onPageChange(currentPage - 1) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text("Back")
                    }
                } else {
                    Spacer(modifier = Modifier)
                }

                if (currentPage < TOTAL_ONBOARDING_PAGES - 1) {
                    Button(
                        onClick = { onPageChange(currentPage + 1) },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text("Continue")
                        Spacer(modifier = Modifier.size(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Button(
                        onClick = onFinish,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(
                            text = "Get Started",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPageLayout(
        title = "Welcome to ARIA",
        subtitle = "An AI-native launcher that learns your patterns and surfaces what you need — before you ask.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureItem(
                title = "Predictive",
                description = "Surfaces the right apps based on time, location, and activity.",
            )
            FeatureItem(
                title = "Proactive",
                description = "Shows information cards from your notifications and calendar.",
            )
            FeatureItem(
                title = "Conversational",
                description = "Chat with an AI that can take actions on your phone.",
            )
            FeatureItem(
                title = "Adaptive",
                description = "Gets smarter every night while your phone charges.",
            )
        }
    }
}

@Composable
private fun PermissionsPage(
    usageStatsGranted: Boolean,
    notificationsGranted: Boolean,
    locationGranted: Boolean,
    activityRecognitionGranted: Boolean,
    calendarGranted: Boolean,
    onGrantUsageStats: () -> Unit,
    onGrantRuntimePermissions: () -> Unit,
) {
    OnboardingPageLayout(
        title = "Permissions",
        subtitle = "ARIA needs these permissions for context-aware predictions. All data stays on your device.",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = "Required",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            PermissionRow(isChecked = usageStatsGranted, onClick = onGrantUsageStats, permissionName = "Usage access")
            Text(
                text = "Allows ARIA to see which apps you use and when.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, bottom = 12.dp),
            )

            Text(
                text = "Recommended",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
            PermissionRow(isChecked = locationGranted, onClick = onGrantRuntimePermissions, permissionName = "Location")
            Text(
                text = "Detects your WiFi network for home/work/venue context.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
            )
            PermissionRow(isChecked = activityRecognitionGranted, onClick = onGrantRuntimePermissions, permissionName = "Activity recognition")
            Text(
                text = "Detects whether you're walking, driving, or stationary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
            )
            PermissionRow(isChecked = calendarGranted, onClick = onGrantRuntimePermissions, permissionName = "Calendar")
            Text(
                text = "Boosts meeting apps when you have upcoming events.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NotificationAccessPage(
    isEnabled: Boolean,
    onEnable: () -> Unit,
) {
    OnboardingPageLayout(
        title = "Notification Access",
        subtitle = "ARIA reads your notifications to create information cards — like email summaries, message counts, and now playing info. No data leaves your device.",
    ) {
        PermissionRow(isChecked = isEnabled, onClick = onEnable, permissionName = "Notification listener")
        Spacer(modifier = Modifier.height(8.dp))
        if (!isEnabled) {
            Button(
                onClick = onEnable,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Enable in Settings")
            }
        } else {
            Text(
                text = "Notification access is enabled.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ReadyPage() {
    OnboardingPageLayout(
        title = "You\u2019re all set",
        subtitle = "ARIA gets smarter every day. The more you use your phone, the better it understands you.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FeatureItem(
                title = "Home screen",
                description = "Predicted apps and information cards appear automatically.",
            )
            FeatureItem(
                title = "Chat",
                description = "Tap \u201cAsk ARIA anything\u201d to open your AI assistant.",
            )
            FeatureItem(
                title = "Nightly learning",
                description = "Predictions re-score each night while your phone charges.",
            )
        }
    }
}

@Composable
private fun FeatureItem(
    title: String,
    description: String,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
