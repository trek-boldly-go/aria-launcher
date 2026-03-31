// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.engine.rules

import com.aria.launcher.aria.data.AriaDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuleModule {

    @Provides
    @Singleton
    fun provideAriaRuleDao(db: AriaDatabase): AriaRuleDao = db.ariaRuleDao()
}
