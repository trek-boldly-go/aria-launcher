// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Discovers what installed apps can do by probing standard Android intents
 * at runtime via [PackageManager.queryIntentActivities].
 *
 * Results are cached in memory and refreshed on first use, then every 24h
 * or when notified of package changes.
 */
@Singleton
class DeviceCapabilityCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLabelResolver: AppLabelResolver,
) {
    data class AppCapability(
        val packageName: String,
        val appLabel: String,
        val capability: String,
        val intentTemplate: String,
        val category: String,
    )

    private val mutex = Mutex()
    private var cachedCapabilities: List<AppCapability> = emptyList()
    private var lastRefreshMs: Long = 0L

    suspend fun getCapabilities(): List<AppCapability> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (cachedCapabilities.isEmpty() || now - lastRefreshMs > REFRESH_INTERVAL_MS) {
            cachedCapabilities = discoverCapabilities()
            lastRefreshMs = now
        }
        cachedCapabilities
    }

    fun invalidateCache() {
        cachedCapabilities = emptyList()
        lastRefreshMs = 0L
    }

    fun hasCapability(category: String): Boolean = cachedCapabilities.any { it.category == category }

    /**
     * Returns a compact summary for the editorial prompt ${capabilities} variable.
     * Groups by category, one line per category.
     */
    suspend fun getCapabilitySummaryForPrompt(): String {
        val caps = getCapabilities()
        if (caps.isEmpty()) return "none discovered"
        return caps.groupBy { it.category }
            .entries
            .sortedBy { it.key }
            .joinToString("\n") { (category, items) ->
                val appList = items
                    .distinctBy { it.packageName }
                    .take(3)
                    .joinToString(", ") { "${it.appLabel} (package:${it.packageName})" }
                "- ${category.replaceFirstChar { it.uppercase() }}: $appList"
            }
    }

    private suspend fun discoverCapabilities(): List<AppCapability> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val results = mutableListOf<AppCapability>()

        for (probe in INTENT_PROBES) {
            try {
                val resolved = pm.queryIntentActivities(probe.intent, PackageManager.MATCH_DEFAULT_ONLY)
                for (info in resolved.take(MAX_APPS_PER_PROBE)) {
                    val label = info.loadLabel(pm)?.toString()
                        ?: appLabelResolver.resolve(info.activityInfo.packageName)
                    results.add(
                        AppCapability(
                            packageName = info.activityInfo.packageName,
                            appLabel = label,
                            capability = probe.capability,
                            intentTemplate = probe.intentTemplate,
                            category = probe.category,
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to probe ${probe.category}: ${e.message}")
            }
        }

        Log.d(TAG, "Discovered ${results.size} capabilities across ${results.map { it.category }.distinct().size} categories")
        results
    }

    private data class IntentProbe(
        val intent: Intent,
        val category: String,
        val capability: String,
        val intentTemplate: String,
    )

    companion object {
        private const val TAG = "ARIA.CapabilityCatalog"
        private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_APPS_PER_PROBE = 3

        private val INTENT_PROBES = listOf(
            IntentProbe(
                intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                category = "phone",
                capability = "Make a phone call",
                intentTemplate = "tel:{number}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                category = "sms",
                capability = "Send a text message",
                intentTemplate = "smsto:{number}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
                category = "email",
                capability = "Send an email",
                intentTemplate = "mailto:{address}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=test")),
                category = "navigation",
                capability = "Navigate to an address",
                intentTemplate = "geo:0,0?q={destination}",
            ),
            IntentProbe(
                intent = Intent(AlarmClock.ACTION_SET_ALARM),
                category = "alarm",
                capability = "Set an alarm",
                intentTemplate = "action:${AlarmClock.ACTION_SET_ALARM}",
            ),
            IntentProbe(
                intent = Intent(AlarmClock.ACTION_SET_TIMER),
                category = "timer",
                capability = "Set a timer",
                intentTemplate = "action:${AlarmClock.ACTION_SET_TIMER}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                },
                category = "calendar",
                capability = "Create a calendar event",
                intentTemplate = "content://com.android.calendar/events",
            ),
            IntentProbe(
                intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                category = "camera",
                capability = "Take a photo",
                intentTemplate = "action:${MediaStore.ACTION_IMAGE_CAPTURE}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
                category = "browser",
                capability = "Open a web page",
                intentTemplate = "https://{url}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" },
                category = "share",
                capability = "Share text with another app",
                intentTemplate = "action:${Intent.ACTION_SEND}",
            ),
            IntentProbe(
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/external/audio/media")),
                category = "music",
                capability = "Play music",
                intentTemplate = "content://media/external/audio/media",
            ),
            IntentProbe(
                intent = Intent(Settings.ACTION_SETTINGS),
                category = "settings",
                capability = "Open device settings",
                intentTemplate = "action:${Settings.ACTION_SETTINGS}",
            ),
        )
    }
}
