// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.chat

import android.content.Context
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.CalendarEventProvider
import com.aria.launcher.aria.data.ContactsRepository
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.LocationProvider
import com.aria.launcher.aria.data.MemoryRepository
import com.aria.launcher.aria.data.WeatherProvider
import com.aria.launcher.aria.engine.AppActivityCatalog
import com.aria.launcher.aria.engine.AppLabelResolver
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import com.aria.launcher.aria.engine.skills.AgentSkillManager
import com.aria.launcher.aria.llm.AriaLlmClient
import com.aria.launcher.aria.llm.LlmProviderManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {

    @Provides
    @Singleton
    fun provideChatState(
        @ApplicationContext context: Context,
        llmProviderManager: LlmProviderManager,
        contextSignalManager: ContextSignalManager,
        memoryRepository: MemoryRepository,
        ariaPreferences: AriaPreferences,
        ariaChatHandler: AriaChatHandler,
        capabilityCatalog: DeviceCapabilityCatalog,
        appActivityCatalog: AppActivityCatalog,
        @AriaLlmClient httpClient: OkHttpClient,
        agentSkillManager: AgentSkillManager,
        appLabelResolver: AppLabelResolver,
        contactsRepository: ContactsRepository,
        calendarEventProvider: CalendarEventProvider,
        weatherProvider: WeatherProvider,
        locationProvider: LocationProvider,
    ): ChatState = ChatState(
        context = context,
        llmProviderManager = llmProviderManager,
        contextSignalManager = contextSignalManager,
        memoryRepo = memoryRepository,
        ariaPreferences = ariaPreferences,
        ariaChatHandler = ariaChatHandler,
        capabilityCatalog = capabilityCatalog,
        appActivityCatalog = appActivityCatalog,
        httpClient = httpClient,
        agentSkillManager = agentSkillManager,
        appLabelResolver = appLabelResolver,
        contactsRepository = contactsRepository,
        calendarEventProvider = calendarEventProvider,
        weatherProvider = weatherProvider,
        locationProvider = locationProvider,
    )
}
