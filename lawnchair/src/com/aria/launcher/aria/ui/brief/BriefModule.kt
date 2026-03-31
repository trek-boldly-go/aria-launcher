// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.ui.brief

import com.aria.launcher.aria.ui.brief.sources.CalendarBriefSource
import com.aria.launcher.aria.ui.brief.sources.MediaBriefSource
import com.aria.launcher.aria.ui.brief.sources.NotificationBriefSource
import com.aria.launcher.aria.ui.brief.sources.SkillBridgeSource
import com.aria.launcher.aria.ui.brief.sources.UsagePatternBriefSource
import com.aria.launcher.aria.ui.brief.sources.VenueBriefSource
import com.aria.launcher.aria.ui.brief.sources.WeatherBriefSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module that wires up the Brief system.
 *
 * Provides the [List<BriefDataSource>] that [BriefAggregator] pulls from.
 * To add a new data source, add it to the list here.
 * MCP server sources will register themselves via this list in Phase 8.
 */
@Module
@InstallIn(SingletonComponent::class)
object BriefModule {

    @Provides
    @Singleton
    fun provideBriefSources(
        calendarBriefSource: CalendarBriefSource,
        notificationBriefSource: NotificationBriefSource,
        mediaBriefSource: MediaBriefSource,
        venueBriefSource: VenueBriefSource,
        skillBridgeSource: SkillBridgeSource,
        weatherBriefSource: WeatherBriefSource,
        usagePatternBriefSource: UsagePatternBriefSource,
    ): List<@JvmSuppressWildcards BriefDataSource> = listOf(
        calendarBriefSource,
        notificationBriefSource,
        mediaBriefSource,
        venueBriefSource,
        skillBridgeSource,
        weatherBriefSource,
        usagePatternBriefSource,
    )
}
