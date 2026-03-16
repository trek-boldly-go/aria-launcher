// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief

import android.util.Log
import com.aria.launcher.aria.engine.AriaContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects [BriefItem]s from all registered [BriefDataSource]s,
 * applies heuristic ranking, and caps at [MAX_BRIEF_ITEMS].
 *
 * Session 9 adds LLM editorial engine integration — until then,
 * this uses a priority-based heuristic sort.
 */
@Singleton
class BriefAggregator @Inject constructor(
    private val sources: List<@JvmSuppressWildcards BriefDataSource>,
) {
    suspend fun buildBrief(context: AriaContext): List<BriefItem> {
        val available = sources.filter { it.isAvailable(context) }
        Log.d(TAG, "Building brief from ${available.size}/${sources.size} available sources")

        val allItems = available.flatMap { source ->
            try {
                source.fetchItems(context)
            } catch (e: Exception) {
                Log.w(TAG, "Source ${source.sourceId} failed", e)
                emptyList()
            }
        }

        // Heuristic ranking — Session 9 replaces with LLM editorial engine
        return heuristicRank(allItems).take(MAX_BRIEF_ITEMS)
    }

    /**
     * Priority-based heuristic ranking.
     * Calendar events within 30 minutes always come first.
     * Critical alerts next, then warnings, then everything else by type priority.
     */
    private fun heuristicRank(items: List<BriefItem>): List<BriefItem> {
        return items.sortedByDescending { item ->
            when (item) {
                is BriefItem.CalendarEvent -> 100
                is BriefItem.AlertAssessed -> when (item.severity) {
                    AlertSeverity.CRITICAL -> 95
                    AlertSeverity.WARNING -> 70
                    AlertSeverity.INFO -> 40
                }
                is BriefItem.ReminderNudge -> 60
                is BriefItem.MediaResume -> 30
                is BriefItem.VenueCard -> 50
                is BriefItem.ProactiveSuggestion -> 45
                is BriefItem.LiveDataCard -> 35
                is BriefItem.ContextBar -> 0 // rendered separately, not in Brief list
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.BriefAggregator"
        const val MAX_BRIEF_ITEMS = 5
    }
}
