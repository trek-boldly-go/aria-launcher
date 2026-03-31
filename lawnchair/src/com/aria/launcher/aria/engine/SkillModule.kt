// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine

import android.content.Context
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.NearbyWifiScanner
import com.aria.launcher.aria.data.SkillDao
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import com.aria.launcher.aria.engine.skills.CalendarSkillExecutor
import com.aria.launcher.aria.engine.skills.NotificationSkillExecutor
import com.aria.launcher.aria.engine.skills.ScheduledSkillExecutor
import com.aria.launcher.aria.engine.skills.VenueSkillExecutor
import com.aria.launcher.aria.engine.skills.WeatherSkillExecutor
import com.aria.launcher.aria.llm.AriaLlmClient
import com.aria.launcher.aria.llm.LlmProviderManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object SkillModule {

    @Provides
    @Singleton
    fun provideSkillExecutorRegistry(
        @ApplicationContext context: Context,
        calendarEventProvider: CalendarEventProvider,
        wifiScanner: NearbyWifiScanner,
        ariaPreferences: AriaPreferences,
        @AriaLlmClient httpClient: OkHttpClient,
        json: Json,
        agentSkillManager: AgentSkillManager,
        llmProviderManager: LlmProviderManager,
    ): SkillExecutorRegistry {
        val executors = listOf(
            NotificationSkillExecutor(json),
            CalendarSkillExecutor(calendarEventProvider, json),
            VenueSkillExecutor(wifiScanner, json),
            WeatherSkillExecutor(context, httpClient, json, ariaPreferences),
        )
        val scheduledExecutor = ScheduledSkillExecutor(
            context,
            agentSkillManager,
            llmProviderManager,
            httpClient,
            json,
        )
        return SkillExecutorRegistry(executors, fallbackExecutor = scheduledExecutor)
    }

    @Provides
    @Singleton
    fun provideAgentSkillManager(
        @ApplicationContext context: Context,
        @AriaLlmClient httpClient: OkHttpClient,
    ): AgentSkillManager = AgentSkillManager(context, httpClient)

    @Provides
    @Singleton
    fun provideSkillOrchestrator(
        skillDao: SkillDao,
        registry: SkillExecutorRegistry,
        agentSkillManager: AgentSkillManager,
    ): SkillOrchestrator = SkillOrchestrator(skillDao, registry, agentSkillManager)
}
