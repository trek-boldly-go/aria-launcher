// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.onboarding

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lawnchair.ui.preferences.components.PermissionRow
import app.lawnchair.ui.theme.LawnchairTheme
import com.aria.launcher.aria.data.AriaNotificationListener
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.llm.LiteRtLmProvider
import com.aria.launcher.aria.llm.LiteRtModelManager
import com.aria.launcher.aria.llm.LlmProviderManager
import com.aria.launcher.aria.ui.AriaHomeState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TOTAL_ONBOARDING_PAGES = 8

class AriaOnboardingActivity : ComponentActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OnboardingEntryPoint {
        fun llmProviderManager(): LlmProviderManager
        fun ariaPreferences(): AriaPreferences
        fun ariaHomeState(): AriaHomeState
        fun liteRtModelManager(): LiteRtModelManager
        fun liteRtLmProvider(): LiteRtLmProvider
    }

    private var currentPage by mutableIntStateOf(0)
    private var usageStatsGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var activityRecognitionGranted by mutableStateOf(false)
    private var calendarGranted by mutableStateOf(false)
    private var notifListenerEnabled by mutableStateOf(false)
    private var isDefaultLauncher by mutableStateOf(false)
    private var homeWifi by mutableStateOf("")
    private var workWifi by mutableStateOf("")

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> refreshPermissionStates() }

    private val roleRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ -> isDefaultLauncher = checkIsDefaultLauncher() }

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
                        isDefaultLauncher = isDefaultLauncher,
                        onRequestDefaultLauncher = { requestDefaultLauncher() },
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
                        ariaPreferences = entryPoint.ariaPreferences(),
                        llmProviderManager = entryPoint.llmProviderManager(),
                        liteRtModelManager = entryPoint.liteRtModelManager(),
                        liteRtLmProvider = entryPoint.liteRtLmProvider(),
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
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        activityRecognitionGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        notifListenerEnabled = isNotificationListenerEnabled()
        isDefaultLauncher = checkIsDefaultLauncher()
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

    private fun checkIsDefaultLauncher(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            return roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultLauncher() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_HOME)
            if (intent != null) {
                roleRequestLauncher.launch(intent)
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
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
    isDefaultLauncher: Boolean,
    onRequestDefaultLauncher: () -> Unit,
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
    ariaPreferences: AriaPreferences,
    llmProviderManager: LlmProviderManager,
    liteRtModelManager: LiteRtModelManager,
    liteRtLmProvider: LiteRtLmProvider,
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

                1 -> DefaultLauncherPage(
                    isDefault = isDefaultLauncher,
                    onRequestDefault = onRequestDefaultLauncher,
                )

                2 -> PermissionsPage(
                    usageStatsGranted = usageStatsGranted,
                    notificationsGranted = notificationsGranted,
                    locationGranted = locationGranted,
                    activityRecognitionGranted = activityRecognitionGranted,
                    calendarGranted = calendarGranted,
                    onGrantUsageStats = onGrantUsageStats,
                    onGrantRuntimePermissions = onGrantRuntimePermissions,
                    ariaPreferences = entryPoint.ariaPreferences(),
                )

                3 -> NotificationAccessPage(
                    isEnabled = notifListenerEnabled,
                    onEnable = onGrantNotifListener,
                )

                4 -> LlmSetupPage(
                    llmProviderManager = llmProviderManager,
                    onQrScanRequested = onQrScan,
                    liteRtModelManager = liteRtModelManager,
                    liteRtLmProvider = liteRtLmProvider,
                )

                5 -> WifiSetupPage(
                    homeWifi = homeWifi,
                    workWifi = workWifi,
                    currentWifi = currentWifi,
                    onHomeWifiChanged = onHomeWifiChanged,
                    onWorkWifiChanged = onWorkWifiChanged,
                )

                6 -> RuleTutorialPage()

                7 -> ReadyPage()
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DefaultLauncherPage(
    isDefault: Boolean,
    onRequestDefault: () -> Unit,
) {
    OnboardingPageLayout(
        title = "Set as Home",
        subtitle = "ARIA replaces your home screen with a context-aware feed. Set it as your default launcher to get started.",
    ) {
        if (isDefault) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "ARIA is your default launcher",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Button(
                onClick = onRequestDefault,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Home,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Set Default Launcher")
            }
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
    ariaPreferences: AriaPreferences,
) {
    val scope = rememberCoroutineScope()
    val contactsAgentEnabled by ariaPreferences.contactsAccessEnabled.collectAsState(initial = false)
    val calendarAgentEnabled by ariaPreferences.calendarAccessEnabled.collectAsState(initial = false)
    val locationAgentEnabled by ariaPreferences.locationAccessEnabled.collectAsState(initial = false)

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

            Text(
                text = "Chat agent access",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                text = "Optional capabilities for the in-launcher chat agent. Each is opt-in; you can change these any time in ARIA settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            AgentAccessToggle(
                title = "Look up contacts in chat",
                description = "Lets the agent resolve names like “text mom” to phone numbers. Contact data will be sent to your configured LLM provider.",
                checked = contactsAgentEnabled,
                onToggle = { newValue ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ariaPreferences.setContactsAccessEnabled(newValue)
                        }
                    }
                },
            )
            AgentAccessToggle(
                title = "Read upcoming calendar events",
                description = "Lets the agent answer “what's on my calendar” and “am I free Saturday” questions.",
                checked = calendarAgentEnabled,
                onToggle = { newValue ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ariaPreferences.setCalendarAccessEnabled(newValue)
                        }
                    }
                },
            )
            AgentAccessToggle(
                title = "Use current location",
                description = "Lets the agent answer weather and “where am I” questions.",
                checked = locationAgentEnabled,
                onToggle = { newValue ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ariaPreferences.setLocationAccessEnabled(newValue)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AgentAccessToggle(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.size(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
        )
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
