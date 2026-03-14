package com.aria.launcher.aria.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

private val Context.llmPrefsStore by preferencesDataStore(name = "aria_llm_prefs")

enum class ProviderType {
    CLAUDE_API_KEY,
    CLAUDE_OAUTH,
    GEMINI,
    OLLAMA,
    OPENAI_COMPATIBLE,
    OPEN_ROUTER,
}

@Singleton
class LlmProviderManager @Inject constructor(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private var cachedProvider: LlmProvider? = null

    val activeProviderType: Flow<ProviderType?> = context.llmPrefsStore.data
        .map { prefs -> prefs[KEY_PROVIDER_TYPE]?.let { ProviderType.valueOf(it) } }

    suspend fun getProvider(): LlmProvider? {
        cachedProvider?.let { return it }
        val prefs = context.llmPrefsStore.data.first()
        val type = prefs[KEY_PROVIDER_TYPE]?.let { ProviderType.valueOf(it) } ?: return null
        return createProvider(type, prefs).also { cachedProvider = it }
    }

    suspend fun configureProvider(
        type: ProviderType,
        apiKey: String? = null,
        serverUrl: String? = null,
        modelId: String? = null,
        refreshToken: String? = null,
    ) {
        context.llmPrefsStore.edit { prefs ->
            prefs[KEY_PROVIDER_TYPE] = type.name
            apiKey?.let { prefs[KEY_API_KEY] = it }
            serverUrl?.let { prefs[KEY_SERVER_URL] = it }
            modelId?.let { prefs[KEY_MODEL_ID] = it }
            refreshToken?.let { prefs[KEY_REFRESH_TOKEN] = it }
        }
        cachedProvider = null
    }

    suspend fun testConnection(): LlmResult {
        val provider = getProvider() ?: return LlmResult.Error("No provider configured")
        return provider.complete(
            systemPrompt = "You are a helpful assistant. Respond with exactly: ARIA connection successful.",
            messages = listOf(ChatMessage(Role.USER, "Test connection.")),
            maxTokens = 32,
        )
    }

    fun clearCache() {
        cachedProvider = null
    }

    private fun createProvider(
        type: ProviderType,
        prefs: androidx.datastore.preferences.core.Preferences,
    ): LlmProvider? {
        val apiKey = prefs[KEY_API_KEY] ?: ""
        val serverUrl = prefs[KEY_SERVER_URL] ?: ""
        val modelId = prefs[KEY_MODEL_ID]
        val refreshToken = prefs[KEY_REFRESH_TOKEN]

        return when (type) {
            ProviderType.CLAUDE_API_KEY -> ClaudeProvider(
                client = client,
                json = json,
                token = apiKey,
                modelId = modelId ?: "claude-sonnet-4-20250514",
            )
            ProviderType.CLAUDE_OAUTH -> ClaudeProvider(
                client = client,
                json = json,
                token = apiKey,
                modelId = modelId ?: "claude-sonnet-4-20250514",
                refreshToken = refreshToken,
                onTokenRefreshed = { newToken, newRefresh ->
                    // Token refresh is fire-and-forget; next getProvider() call will re-read
                },
            )
            ProviderType.GEMINI -> OpenAICompatibleProvider.gemini(
                client = client,
                json = json,
                apiKey = apiKey,
            )
            ProviderType.OLLAMA -> OllamaProvider(
                client = client,
                json = json,
                serverUrl = serverUrl,
                modelId = modelId ?: "qwen2.5:7b",
            )
            ProviderType.OPENAI_COMPATIBLE -> OpenAICompatibleProvider(
                client = client,
                json = json,
                baseUrl = serverUrl,
                apiKey = apiKey,
                modelId = modelId ?: "gpt-4o",
            )
            ProviderType.OPEN_ROUTER -> OpenAICompatibleProvider.openRouter(
                client = client,
                json = json,
                apiKey = apiKey,
                modelId = modelId ?: "anthropic/claude-sonnet-4",
            )
        }
    }

    companion object {
        private val KEY_PROVIDER_TYPE = stringPreferencesKey("provider_type")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_MODEL_ID = stringPreferencesKey("model_id")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }
}
