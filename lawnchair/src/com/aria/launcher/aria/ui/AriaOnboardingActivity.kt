package com.aria.launcher.aria.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lawnchair.ui.preferences.components.PermissionRow
import app.lawnchair.ui.theme.LawnchairTheme

/**
 * First-launch onboarding activity that requests the permissions ARIA needs
 * for context-aware predictions. Launched once from LawnchairLauncher.onCreate().
 *
 * Permissions requested:
 * - PACKAGE_USAGE_STATS (special — opens system Settings)
 * - POST_NOTIFICATIONS (runtime, Android 13+)
 * - ACCESS_FINE_LOCATION (runtime — for WiFi SSID context signal)
 * - ACTIVITY_RECOGNITION (runtime — for activity detection)
 * - READ_CALENDAR (runtime — for calendar-aware prediction boosts)
 */
class AriaOnboardingActivity : ComponentActivity() {

    private var usageStatsGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var locationGranted by mutableStateOf(false)
    private var activityRecognitionGranted by mutableStateOf(false)
    private var calendarGranted by mutableStateOf(false)

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        refreshPermissionStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionStates()

        setContent {
            LawnchairTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OnboardingScreen(
                        usageStatsGranted = usageStatsGranted,
                        notificationsGranted = notificationsGranted,
                        locationGranted = locationGranted,
                        activityRecognitionGranted = activityRecognitionGranted,
                        calendarGranted = calendarGranted,
                        onGrantUsageStats = { openUsageAccessSettings() },
                        onGrantRuntimePermissions = { requestRuntimePermissions() },
                        onContinue = { finishOnboarding() },
                        onSkip = { finishOnboarding() },
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
            true // Not needed before Android 13
        }
        locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        activityRecognitionGranted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
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

    private fun openUsageAccessSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
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

    private fun finishOnboarding() {
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
@Composable
private fun OnboardingScreen(
    usageStatsGranted: Boolean,
    notificationsGranted: Boolean,
    locationGranted: Boolean,
    activityRecognitionGranted: Boolean,
    calendarGranted: Boolean,
    onGrantUsageStats: () -> Unit,
    onGrantRuntimePermissions: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val allGranted = usageStatsGranted && notificationsGranted && locationGranted &&
        activityRecognitionGranted && calendarGranted
    val usageStatsAndSomeGranted = usageStatsGranted && (locationGranted || activityRecognitionGranted || calendarGranted)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Welcome to ARIA",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "ARIA learns which apps you need based on your context — time of day, location, and activity. Grant these permissions so ARIA can provide smart predictions.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Usage Stats — special permission, separate flow
        Text(
            text = "Required",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        PermissionRow(
            isChecked = usageStatsGranted,
            onClick = onGrantUsageStats,
            permissionName = "Usage access",
        )

        Text(
            text = "Allows ARIA to see which apps you use and when. Opens system settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, bottom = 16.dp),
        )

        // Runtime permissions
        Text(
            text = "Recommended",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
        )

        PermissionRow(
            isChecked = locationGranted,
            onClick = onGrantRuntimePermissions,
            permissionName = "Location",
        )
        Text(
            text = "Detects your WiFi network to distinguish home, work, and other contexts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
        )

        PermissionRow(
            isChecked = activityRecognitionGranted,
            onClick = onGrantRuntimePermissions,
            permissionName = "Activity recognition",
        )
        Text(
            text = "Detects whether you're walking, driving, or stationary for commute-aware suggestions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
        )

        PermissionRow(
            isChecked = calendarGranted,
            onClick = onGrantRuntimePermissions,
            permissionName = "Calendar",
        )
        Text(
            text = "Boosts meeting-related apps when you have upcoming events.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionRow(
                isChecked = notificationsGranted,
                onClick = onGrantRuntimePermissions,
                permissionName = "Notifications",
            )
            Text(
                text = "Allows ARIA to show status updates about prediction scoring.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp, bottom = 8.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(
                text = if (allGranted) "Get started" else "Continue",
                fontSize = 16.sp,
            )
        }

        if (!allGranted) {
            TextButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text("Skip for now")
            }
        }
    }
}
