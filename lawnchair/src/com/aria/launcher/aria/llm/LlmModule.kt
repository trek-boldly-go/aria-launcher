package com.aria.launcher.aria.llm

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AriaLlmClient

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    @AriaLlmClient
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideLiteRtModelManager(
        @ApplicationContext context: Context,
    ): LiteRtModelManager = LiteRtModelManager(context)

    @Provides
    @Singleton
    fun provideLiteRtLmProvider(
        modelManager: LiteRtModelManager,
    ): LiteRtLmProvider = LiteRtLmProvider(modelManager)

    @Provides
    @Singleton
    fun provideLlmProviderManager(
        @ApplicationContext context: Context,
        @AriaLlmClient client: OkHttpClient,
        json: Json,
        liteRtLmProvider: LiteRtLmProvider,
        modelManager: LiteRtModelManager,
    ): LlmProviderManager = LlmProviderManager(context, client, json, liteRtLmProvider, modelManager)
}
