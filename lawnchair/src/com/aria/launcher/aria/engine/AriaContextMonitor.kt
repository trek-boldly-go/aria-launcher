// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.content.Context
import android.util.Log
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.NearbyWifiScanner
import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.engine.rules.AriaRuleEvaluator
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Monitors context signal changes and emits [AriaContext] snapshots.
 * Debounces by [DEBOUNCE_MS] to prevent rapid-fire Brief regeneration
 * when signals (WiFi, activity) bounce. Uses [bucketHash] so the Brief
 * only regenerates on meaningful context changes.
 */
@Singleton
class AriaContextMonitor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val contextSignalManager: ContextSignalManager,
    private val calendarEventProvider: CalendarEventProvider,
    private val usageDataRepository: UsageDataRepository,
    private val wifiScanner: NearbyWifiScanner,
    private val ariaPreferences: AriaPreferences,
    private val ssidClassificationService: SsidClassificationService,
    private val ruleEvaluator: AriaRuleEvaluator,
    private val appLabelResolver: AppLabelResolver,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Suppress("ktlint:standard:backing-property-naming")
    private val _context = MutableStateFlow<AriaContext?>(null)
    val contextChanges: StateFlow<AriaContext?> = _context.asStateFlow()

    private var cachedHomeWifi: String? = null
    private var cachedWorkWifi: String? = null

    fun start() {
        scope.launch {
            cachedHomeWifi = ariaPreferences.getHomeWifiSsid()
            cachedWorkWifi = ariaPreferences.getWorkWifiSsid()

            // Build initial context
            rebuildContext()

            // Watch for signal changes — debounce 30s to avoid rapid-fire rebuilds
            combine(
                contextSignalManager.wifiSsid,
                contextSignalManager.isCharging,
                contextSignalManager.detectedActivity,
                contextSignalManager.isAndroidAutoConnected,
            ) { wifi, charging, activity, auto ->
                // Combine into a simple change signal
                listOf(wifi, charging, activity, auto).hashCode()
            }
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collectLatest {
                    rebuildContext()
                }
        }
    }

    /** Force a context rebuild — called on launcher resume. */
    suspend fun refresh() {
        rebuildContext()
    }

    private suspend fun rebuildContext() {
        try {
            wifiScanner.refresh()
            val baseContext = AriaContext.build(
                appContext = appContext,
                contextSignalManager = contextSignalManager,
                calendarEventProvider = calendarEventProvider,
                usageDataRepository = usageDataRepository,
                wifiScanner = wifiScanner,
                homeWifiSsid = cachedHomeWifi,
                workWifiSsid = cachedWorkWifi,
                appLabelResolver = appLabelResolver,
            )

            // Session 9: classify the current SSID venue
            val venueContext = enrichWithVenue(baseContext)

            // Session 10: evaluate rules and attach fired rules to context
            val firedRules = ruleEvaluator.evaluate(venueContext)
            val newContext = venueContext.copy(firedRules = firedRules)

            val oldHash = _context.value?.bucketHash()
            val newHash = newContext.bucketHash()

            if (oldHash != newHash) {
                _context.value = newContext
                Log.d(TAG, "Context changed: ${newContext.contextKey.toStringKey()}, hash=$newHash")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rebuild context", e)
        }
    }

    private suspend fun enrichWithVenue(context: AriaContext): AriaContext {
        val ssid = context.wifiSsid ?: return context
        return try {
            val venueCategory = ssidClassificationService.classifyIfNeeded(ssid)
            val visitContext = inferVisitContext(
                venueCategory = venueCategory,
                visitCount = 0, // visit tracking added in a future session
                averageVisitDurationMinutes = 0,
                dayType = context.contextKey.dayType,
                calendarEvents = context.upcomingEvents,
            )
            context.copy(
                currentVenueCategory = venueCategory.name,
                visitContext = visitContext.name,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Venue classification failed for '$ssid'", e)
            context
        }
    }

    companion object {
        private const val TAG = "ARIA.ContextMonitor"
        private const val DEBOUNCE_MS = 30_000L
    }
}
