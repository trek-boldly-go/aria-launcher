// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.engine

import android.content.Context
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.NearbyWifiScanner
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.engine.skills.CalendarSkillExecutor
import com.aria.launcher.aria.engine.skills.NotificationSkillExecutor
import com.aria.launcher.aria.engine.skills.VenueSkillExecutor
import com.aria.launcher.aria.engine.skills.WeatherSkillExecutor
import com.aria.launcher.aria.llm.AriaLlmClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SkillModule {

    @Provides
    @Singleton
    fun provideSkillExecutorRegistry(
        @ApplicationContext context: Context,
        calendarEventProvider: CalendarEventProvider,
        wifiScanner: NearbyWifiScanner,
        @AriaLlmClient httpClient: OkHttpClient,
        json: Json,
    ): SkillExecutorRegistry {
        val executors = listOf(
            NotificationSkillExecutor(json),
            CalendarSkillExecutor(calendarEventProvider, json),
            VenueSkillExecutor(wifiScanner, json),
            WeatherSkillExecutor(context, httpClient, json),
        )
        return SkillExecutorRegistry(executors)
    }

    @Provides
    @Singleton
    fun provideSkillOrchestrator(
        skillDao: SkillDao,
        registry: SkillExecutorRegistry,
    ): SkillOrchestrator = SkillOrchestrator(skillDao, registry)
}
