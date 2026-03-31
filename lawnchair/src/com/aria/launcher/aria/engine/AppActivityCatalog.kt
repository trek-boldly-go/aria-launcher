// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.util.DisplayMetrics
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discovers exported activities for installed apps using [PackageManager].
 *
 * This enables ARIA's LLM to navigate directly to specific app screens
 * (e.g., Robinhood's Portfolio) instead of only opening the main activity.
 *
 * Activities are scanned lazily per-app and cached in memory with a 24h TTL.
 * The cache is invalidated by [PackageChangeReceiver] on app install/uninstall.
 */
@Singleton
class AppActivityCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLabelResolver: AppLabelResolver,
) {
    private val cache = ConcurrentHashMap<String, CachedActivities>()

    /**
     * Returns the exported, non-default activities for [packageName].
     * Results are cached for [CACHE_TTL_MS].
     */
    suspend fun getActivities(packageName: String): List<DiscoveredActivity> = withContext(Dispatchers.IO) {
        val cached = cache[packageName]
        if (cached != null && !cached.isExpired()) {
            return@withContext cached.activities
        }
        val activities = discoverActivities(packageName)
        cache[packageName] = CachedActivities(activities, System.currentTimeMillis())
        activities
    }

    /**
     * Returns a compact summary of activities for the given packages, suitable
     * for inclusion in an LLM prompt.
     *
     * Example output:
     * ```
     * Robinhood: Portfolio, Trading, Transfers
     * Spotify: Search, Your Library
     * ```
     */
    suspend fun getPromptSummary(
        packages: List<String>,
        maxPerApp: Int = 3,
    ): String {
        val lines = packages.mapNotNull { pkg ->
            val activities = getActivities(pkg)
            if (activities.isEmpty()) return@mapNotNull null
            val appLabel = appLabelResolver.resolve(pkg)
            val activityLabels = activities.take(maxPerApp).joinToString(", ") { it.label }
            "$appLabel: $activityLabels"
        }
        return lines.joinToString("\n")
    }

    /** Removes a single package from the cache. */
    fun invalidatePackage(packageName: String) {
        cache.remove(packageName)
    }

    /** Clears the entire cache. */
    fun invalidate() {
        cache.clear()
    }

    private fun discoverActivities(packageName: String): List<DiscoveredActivity> {
        val pm = context.packageManager
        val pkgInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }

        val allActivities = pkgInfo.activities ?: return emptyList()
        val appLabel = appLabelResolver.resolve(packageName)

        // Determine the default launch activity so we can exclude it
        val defaultComponent = pm.getLaunchIntentForPackage(packageName)
            ?.resolveActivityInfo(pm, 0)
            ?.name

        val discovered = allActivities
            .filter { it.exported }
            .filter { isEnabled(pm, packageName, it.name) }
            .filter { it.name != defaultComponent }
            .mapNotNull { activityInfo ->
                val label = resolveActivityLabel(pm, packageName, activityInfo)
                // Skip activities whose label matches the app name (generic)
                // or is just the class name with no readable label
                if (label.equals(appLabel, ignoreCase = true)) return@mapNotNull null
                if (label == activityInfo.name) return@mapNotNull null
                DiscoveredActivity(
                    componentName = ComponentName(packageName, activityInfo.name),
                    label = label,
                )
            }
            .distinctBy { it.label }
            .take(MAX_ACTIVITIES_PER_APP)

        if (discovered.isNotEmpty()) {
            Log.d(TAG, "$packageName: ${discovered.size} activities — ${discovered.joinToString { it.label }}")
        }
        return discovered
    }

    private fun resolveActivityLabel(
        pm: PackageManager,
        packageName: String,
        activityInfo: ActivityInfo,
    ): String {
        // Try loading the activity's own label resource
        if (activityInfo.labelRes != 0) {
            try {
                val appRes = pm.getResourcesForApplication(packageName)
                appRes.updateConfiguration(
                    context.resources.configuration,
                    DisplayMetrics(),
                )
                return appRes.getString(activityInfo.labelRes)
            } catch (_: Exception) {
                // Fall through to class name extraction
            }
        }
        // Fall back to a readable name derived from the class name
        return activityInfo.name
            .substringAfterLast('.')
            .replace(Regex("Activity$"), "")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .trim()
            .ifEmpty { activityInfo.name.substringAfterLast('.') }
    }

    private fun isEnabled(pm: PackageManager, packageName: String, activityName: String): Boolean {
        val component = ComponentName(packageName, activityName)
        return when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
            -> false

            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true

            // Default — respect the manifest value
            else -> true
        }
    }

    private data class CachedActivities(
        val activities: List<DiscoveredActivity>,
        val cachedAtMs: Long,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - cachedAtMs > CACHE_TTL_MS
    }

    companion object {
        private const val TAG = "ARIA.ActivityCatalog"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_ACTIVITIES_PER_APP = 10
    }
}

/**
 * An exported activity discovered within an installed app.
 */
data class DiscoveredActivity(
    val componentName: ComponentName,
    val label: String,
)
