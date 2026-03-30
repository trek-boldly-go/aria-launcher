// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.chat

import android.content.Context
import com.aria.launcher.aria.data.AriaPreferences
import com.aria.launcher.aria.data.ContextSignalManager
import com.aria.launcher.aria.data.UserMemoryDao
import com.aria.launcher.aria.engine.DeviceCapabilityCatalog
import com.aria.launcher.aria.llm.LlmProviderManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ChatModule {

    @Provides
    @Singleton
    fun provideChatState(
        @ApplicationContext context: Context,
        llmProviderManager: LlmProviderManager,
        contextSignalManager: ContextSignalManager,
        userMemoryDao: UserMemoryDao,
        ariaPreferences: AriaPreferences,
        ariaChatHandler: AriaChatHandler,
        capabilityCatalog: DeviceCapabilityCatalog,
    ): ChatState = ChatState(
        context,
        llmProviderManager,
        contextSignalManager,
        userMemoryDao,
        ariaPreferences,
        ariaChatHandler,
        capabilityCatalog,
    )
}
