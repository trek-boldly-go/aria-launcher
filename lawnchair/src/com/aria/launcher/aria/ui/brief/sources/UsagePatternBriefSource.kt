// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.sources

import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.engine.AppActivityCatalog
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates proactive suggestion cards only when a specific deep-link action is available.
 *
 * Will NOT produce generic "You usually open X now" cards — the predicted apps row
 * already handles frequently-used apps. Only surfaces cards when [AppActivityCatalog]
 * provides a specific screen to deep-link into (e.g., "Portfolio" in Robinhood).
 */
@Singleton
class UsagePatternBriefSource @Inject constructor(
    private val usageDataRepository: UsageDataRepository,
    private val appLabelResolver: AppLabelResolver,
    private val appActivityCatalog: AppActivityCatalog,
) : BriefDataSource {

    override val sourceId: String = "usage_patterns"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val contextKey = context.contextKey.toStringKey()
        val predictions = usageDataRepository.getTopApps(contextKey, limit = 5)
        if (predictions.isEmpty()) return emptyList()

        val recentPackages = context.recentAppPackages.toSet()

        // Only produce cards when we have a specific deep-link action to offer.
        // Generic "you usually open X" cards are useless — the predicted apps row
        // already surfaces frequently-used apps. Cards must earn their space.
        return predictions
            .filter { it.packageName !in recentPackages }
            .take(3)
            .mapNotNull { prediction ->
                val activities = appActivityCatalog.getActivities(prediction.packageName)
                val topActivity = activities.firstOrNull() ?: return@mapNotNull null

                val component = topActivity.componentName.flattenToShortString()
                BriefItem.ProactiveSuggestion(
                    headline = topActivity.label,
                    rationale = appLabelResolver.resolve(prediction.packageName),
                    action = BriefAction(
                        label = "Open",
                        intentUri = "intent:#Intent;component=$component;end",
                    ),
                )
            }
            .take(2)
    }
}
