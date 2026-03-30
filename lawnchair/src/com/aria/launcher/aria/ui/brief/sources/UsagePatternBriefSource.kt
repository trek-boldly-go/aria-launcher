// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.sources

import com.aria.launcher.aria.data.UsageDataRepository
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates proactive suggestion cards based on app usage patterns.
 *
 * When no LLM is configured, this source provides "You usually open X around now"
 * cards for apps the user typically opens in the current time/day context but
 * hasn't opened recently.
 */
@Singleton
class UsagePatternBriefSource @Inject constructor(
    private val usageDataRepository: UsageDataRepository,
) : BriefDataSource {

    override val sourceId: String = "usage_patterns"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val contextKey = context.contextKey.toStringKey()
        val predictions = usageDataRepository.getTopApps(contextKey, limit = 5)
        if (predictions.isEmpty()) return emptyList()

        val recentPackages = context.recentAppPackages.toSet()

        return predictions
            .filter { it.packageName !in recentPackages }
            .take(2)
            .map { prediction ->
                val appLabel = prediction.packageName
                    .substringAfterLast('.')
                    .replaceFirstChar { it.uppercase() }
                BriefItem.ProactiveSuggestion(
                    headline = "You usually open $appLabel now",
                    rationale = "Based on your usage pattern",
                    action = BriefAction(
                        label = "Open",
                        intentUri = "package:${prediction.packageName}",
                    ),
                )
            }
    }
}
