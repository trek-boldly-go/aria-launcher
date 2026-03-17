package com.aria.launcher.aria.data

import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AriaDataModule {

    @Provides
    @Singleton
    fun provideAriaDatabase(@ApplicationContext context: Context): AriaDatabase = AriaDatabase.getInstance(context)

    @Provides
    @Singleton
    fun provideAppUsageEventDao(db: AriaDatabase): AppUsageEventDao = db.appUsageEventDao()

    @Provides
    @Singleton
    fun provideAppPredictionDao(db: AriaDatabase): AppPredictionDao = db.appPredictionDao()

    @Provides
    @Singleton
    fun provideUsageDataRepository(@ApplicationContext context: Context): UsageDataRepository = UsageDataRepository(context)

    @Provides
    @Singleton
    fun provideSkillDao(db: AriaDatabase): SkillDao = db.skillDao()

    @Provides
    @Singleton
    fun provideUserMemoryDao(db: AriaDatabase): UserMemoryDao = db.userMemoryDao()

    @Provides
    @Singleton
    fun provideUsageStatsManager(@ApplicationContext context: Context): UsageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Provides
    @Singleton
    fun provideSsidClassificationDao(db: AriaDatabase): SsidClassificationDao = db.ssidClassificationDao()

    @Provides
    @Singleton
    fun provideAppChainDao(db: AriaDatabase): AppChainDao = db.appChainDao()
}
