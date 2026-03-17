// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.ui.brief.sources

import com.aria.launcher.aria.data.SkillAction
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.data.SkillResult
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Bridges pre-computed SkillResults from the SkillOrchestrator into the Brief system.
 *
 * Weather results (weather.forecast, weather.alerts) are excluded here — they surface
 * in the ContextBar greeting area instead. All other skill results appear as
 * ProactiveSuggestion cards with a tappable action.
 */
@Singleton
class SkillBridgeSource @Inject constructor(
    private val skillDao: SkillDao,
    private val json: Json,
) : BriefDataSource {

    override val sourceId = "skill_bridge"

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> =
        skillDao.getActiveResults()
            .filter { it.skillId !in WEATHER_SKILL_IDS }
            .map { it.toBriefItem() }

    private fun SkillResult.toBriefItem(): BriefItem.ProactiveSuggestion {
        val firstAction = runCatching {
            json.decodeFromString<List<SkillAction>>(actions).firstOrNull()
        }.getOrNull()

        return BriefItem.ProactiveSuggestion(
            headline = title,
            rationale = body,
            action = BriefAction(
                label = firstAction?.label ?: "Open",
                intentUri = firstAction?.toIntentUri(),
            ),
            dismissible = true,
        )
    }

    companion object {
        private val WEATHER_SKILL_IDS = setOf("weather.forecast", "weather.alerts")
    }
}

private fun SkillAction.toIntentUri(): String? = when (type) {
    "OPEN_APP" -> "package:$payload"
    "DEEP_LINK" -> payload
    "INTENT" -> payload
    else -> null
}
