// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief.sources

import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.AriaContext
import com.aria.launcher.aria.ui.brief.AlertSeverity
import com.aria.launcher.aria.ui.brief.BriefAction
import com.aria.launcher.aria.ui.brief.BriefDataSource
import com.aria.launcher.aria.ui.brief.BriefItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Surfaces severe weather conditions as AlertAssessed cards in the Brief.
 *
 * Normal weather is handled by the ContextBar (greeting area).
 * This source only fires when conditions are genuinely severe:
 * thunderstorms, high winds (>= 45 mph gusts), or advisory-level sustained winds.
 */
@Singleton
class WeatherBriefSource @Inject constructor(
    private val weatherProvider: WeatherProvider,
) : BriefDataSource {

    override val sourceId = "weather"

    override val requiresNetwork = true

    override suspend fun fetchItems(context: AriaContext): List<BriefItem> {
        val snapshot = weatherProvider.getWeather() ?: return emptyList()
        if (!snapshot.isSevere()) return emptyList()

        val severity = when {
            snapshot.weatherCode >= 96 -> AlertSeverity.CRITICAL

            // hail
            snapshot.weatherCode >= 95 -> AlertSeverity.WARNING

            // thunderstorm
            snapshot.windGustsMph != null && snapshot.windGustsMph >= 55 -> AlertSeverity.CRITICAL

            snapshot.windGustsMph != null && snapshot.windGustsMph >= 45 -> AlertSeverity.WARNING

            snapshot.windSpeedMph >= 30 -> AlertSeverity.WARNING

            else -> AlertSeverity.INFO
        }

        val headline = when {
            snapshot.weatherCode >= 95 ->
                "${snapshot.icon} ${snapshot.condition}"

            snapshot.windGustsMph != null && snapshot.windGustsMph >= 45 ->
                "\u26A0\uFE0F High wind gusts up to ${snapshot.windGustsMph} mph"

            else ->
                "\u26A0\uFE0F Sustained winds ${snapshot.windSpeedMph} mph"
        }

        val subtext = when {
            snapshot.weatherCode >= 95 && snapshot.windGustsMph != null ->
                "Wind gusts ${snapshot.windGustsMph} mph · ${snapshot.tempF}°F"

            snapshot.windGustsMph != null && snapshot.windGustsMph >= 45 ->
                "Secure loose outdoor items"

            else ->
                "Exercise caution outdoors"
        }

        return listOf(
            BriefItem.AlertAssessed(
                icon = snapshot.icon,
                headline = headline,
                subtext = subtext,
                severity = severity,
                action = BriefAction(
                    label = "Weather",
                    intentUri = "package:com.google.android.apps.weather",
                ),
            ),
        )
    }
}
