package com.aria.launcher.aria.llm

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

private val Context.llmPrefsStore by preferencesDataStore(name = "aria_llm_prefs")

enum class ProviderType {
    CLAUDE_API_KEY,
    CLAUDE_OAUTH,
    GEMINI,
    OLLAMA,
    OPENAI_COMPATIBLE,
    OPEN_ROUTER,
    LITERT,
}

val ProviderType.displayName: String get() = when (this) {
    ProviderType.CLAUDE_API_KEY -> "Claude"
    ProviderType.CLAUDE_OAUTH -> "Claude (OAuth)"
    ProviderType.GEMINI -> "Gemini"
    ProviderType.OLLAMA -> "Ollama"
    ProviderType.OPENAI_COMPATIBLE -> "OpenAI Compatible"
    ProviderType.OPEN_ROUTER -> "OpenRouter"
    ProviderType.LITERT -> "On-device"
}

val ProviderType.isLocal: Boolean get() = this == ProviderType.LITERT

data class ProviderStatus(
    val type: ProviderType,
    val displayName: String,
    val isLocal: Boolean,
    val modelId: String?,
    val serverUrl: String?,
)

data class SavedProviderConfig(
    val type: ProviderType?,
    val apiKey: String,
    val serverUrl: String,
    val modelId: String,
    val authConfig: AuthConfig,
)

@Singleton
class LlmProviderManager @Inject constructor(
    private val context: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val liteRtLmProvider: LiteRtLmProvider,
    private val modelManager: LiteRtModelManager,
) {
    private var cachedProvider: LlmProvider? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Sync modelManager.selectedModel from DataStore on construction
        runBlocking {
            val prefs = context.llmPrefsStore.data.first()
            val modelTag = prefs[KEY_ON_DEVICE_MODEL]
            val model = OnDeviceModel.entries.firstOrNull { it.modelIdTag == modelTag }
            if (model != null) {
                modelManager.selectedModel = model
            }
        }
    }

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
        authConfig: AuthConfig? = null,
    ) {
        context.llmPrefsStore.edit { prefs ->
            val previousType = prefs[KEY_PROVIDER_TYPE]?.let {
                runCatching { ProviderType.valueOf(it) }.getOrNull()
            }

            // When switching to LITERT, save the current remote provider as fallback
            if (type == ProviderType.LITERT) {
                previousType?.let { prev ->
                    if (prev != ProviderType.LITERT) {
                        prefs[KEY_FALLBACK_PROVIDER] = prev.name
                    }
                }
            }

            // Clear stale keys from the previous provider when switching types
            if (previousType != null && previousType != type) {
                prefs.remove(KEY_MODEL_ID)
                prefs.remove(KEY_SERVER_URL)
                prefs.remove(KEY_AUTH_CONFIG)
                prefs.remove(KEY_REFRESH_TOKEN)
            }

            prefs[KEY_PROVIDER_TYPE] = type.name
            apiKey?.let { prefs[KEY_API_KEY] = it }
            serverUrl?.let { prefs[KEY_SERVER_URL] = it }
            modelId?.let { prefs[KEY_MODEL_ID] = it }
            refreshToken?.let { prefs[KEY_REFRESH_TOKEN] = it }
            authConfig?.let { prefs[KEY_AUTH_CONFIG] = it.encode() }
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

    suspend fun getSavedConfig(): SavedProviderConfig {
        val prefs = context.llmPrefsStore.data.first()
        return SavedProviderConfig(
            type = prefs[KEY_PROVIDER_TYPE]?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() },
            apiKey = prefs[KEY_API_KEY] ?: "",
            serverUrl = prefs[KEY_SERVER_URL] ?: "",
            modelId = prefs[KEY_MODEL_ID] ?: "",
            authConfig = AuthConfig.decode(prefs[KEY_AUTH_CONFIG]),
        )
    }

    /** The previously active remote provider, saved when switching to LITERT. */
    val fallbackProviderType: Flow<ProviderType?> = context.llmPrefsStore.data
        .map { prefs ->
            prefs[KEY_FALLBACK_PROVIDER]?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() }
        }

    /** Switch back from LITERT to the previously saved remote provider. */
    suspend fun restoreFallbackProvider(): Boolean {
        val prefs = context.llmPrefsStore.data.first()
        val fallbackType = prefs[KEY_FALLBACK_PROVIDER]?.let {
            runCatching { ProviderType.valueOf(it) }.getOrNull()
        } ?: return false
        context.llmPrefsStore.edit { p ->
            p[KEY_PROVIDER_TYPE] = fallbackType.name
        }
        cachedProvider = null
        return true
    }

    /** Rich status for the active provider, suitable for UI display. */
    val providerStatus: Flow<ProviderStatus?> = context.llmPrefsStore.data
        .map { prefs ->
            val type = prefs[KEY_PROVIDER_TYPE]?.let {
                runCatching { ProviderType.valueOf(it) }.getOrNull()
            } ?: return@map null
            ProviderStatus(
                type = type,
                displayName = type.displayName,
                isLocal = type.isLocal,
                modelId = prefs[KEY_MODEL_ID],
                serverUrl = prefs[KEY_SERVER_URL],
            )
        }

    /** The saved auth config, for pre-filling Ollama setup UI. */
    val savedAuthConfig: Flow<AuthConfig> = context.llmPrefsStore.data
        .map { prefs -> AuthConfig.decode(prefs[KEY_AUTH_CONFIG]) }

    /** The saved server URL, for pre-filling Ollama setup UI. */
    val savedServerUrl: Flow<String?> = context.llmPrefsStore.data
        .map { prefs -> prefs[KEY_SERVER_URL] }

    /** Fetch available models from an Ollama server. */
    suspend fun fetchOllamaModels(
        serverUrl: String,
        authConfig: AuthConfig = AuthConfig.None,
    ): Result<List<OllamaModel>> = OllamaProvider.fetchAvailableModels(client, serverUrl, authConfig)

    val selectedOnDeviceModel: Flow<OnDeviceModel> = context.llmPrefsStore.data
        .map { prefs ->
            val tag = prefs[KEY_ON_DEVICE_MODEL]
            OnDeviceModel.entries.firstOrNull { it.modelIdTag == tag } ?: OnDeviceModel.GEMMA_1B
        }

    suspend fun getSelectedOnDeviceModel(): OnDeviceModel {
        val tag = context.llmPrefsStore.data.first()[KEY_ON_DEVICE_MODEL]
        return OnDeviceModel.entries.firstOrNull { it.modelIdTag == tag } ?: OnDeviceModel.GEMMA_1B
    }

    /**
     * Switches the on-device model variant. Persists the selection, updates the model manager,
     * invalidates the running engine (frees GPU memory), clears the cached provider, and
     * deletes the old model file to reclaim storage.
     */
    suspend fun setSelectedOnDeviceModel(model: OnDeviceModel) {
        val oldModel = modelManager.selectedModel
        if (oldModel == model) return

        // Persist to DataStore
        context.llmPrefsStore.edit { prefs ->
            prefs[KEY_ON_DEVICE_MODEL] = model.modelIdTag
        }

        // Update in-memory selection
        modelManager.selectedModel = model

        // Tear down the old engine so GPU resources are freed
        liteRtLmProvider.invalidateEngine()

        // Clear cached provider so next getProvider() rebuilds
        cachedProvider = null

        // Delete old model file to reclaim storage
        if (oldModel != model) {
            modelManager.deleteModelFile(oldModel)
        }

        Log.i(TAG, "Switched on-device model: ${oldModel.displayName} → ${model.displayName}")
    }

    val hfToken: Flow<String?> = context.llmPrefsStore.data
        .map { it[KEY_HF_TOKEN] }

    suspend fun getHfToken(): String? = context.llmPrefsStore.data.first()[KEY_HF_TOKEN]

    suspend fun setHfToken(token: String?) {
        context.llmPrefsStore.edit { prefs ->
            if (token.isNullOrBlank()) prefs.remove(KEY_HF_TOKEN) else prefs[KEY_HF_TOKEN] = token
        }
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
                isOAuth = true,
                refreshToken = refreshToken,
                onTokenRefreshed = { newToken, newRefresh ->
                    Log.d(TAG, "OAuth token refreshed, persisting to DataStore")
                    scope.launch {
                        configureProvider(
                            type = ProviderType.CLAUDE_OAUTH,
                            apiKey = newToken,
                            refreshToken = newRefresh,
                        )
                    }
                },
            )

            ProviderType.GEMINI -> GeminiProvider(
                client = client,
                json = json,
                apiKey = apiKey,
                modelId = modelId ?: "gemini-2.5-flash",
            )

            ProviderType.OLLAMA -> OllamaProvider(
                client = client,
                json = json,
                serverUrl = serverUrl,
                modelId = modelId ?: "qwen2.5:7b",
                authConfig = AuthConfig.decode(prefs[KEY_AUTH_CONFIG]),
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

            ProviderType.LITERT -> {
                // Engine warms up lazily on first inference call (~10s one-time cost).
                // Fall back to remote provider only if model isn't downloaded yet.
                if (liteRtLmProvider.isReady() || modelManager.isModelDownloaded()) {
                    liteRtLmProvider
                } else {
                    Log.w(TAG, "LiteRT model not downloaded, falling back to previous remote provider")
                    val fallbackType = prefs[KEY_FALLBACK_PROVIDER]?.let {
                        runCatching { ProviderType.valueOf(it) }.getOrNull()
                    }
                    if (fallbackType != null && fallbackType != ProviderType.LITERT) {
                        createProvider(fallbackType, prefs)
                    } else {
                        null
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ARIA.LlmProviderManager"
        private val KEY_PROVIDER_TYPE = stringPreferencesKey("provider_type")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_MODEL_ID = stringPreferencesKey("model_id")
        private val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val KEY_FALLBACK_PROVIDER = stringPreferencesKey("fallback_provider")
        private val KEY_AUTH_CONFIG = stringPreferencesKey("auth_config")
        private val KEY_HF_TOKEN = stringPreferencesKey("hf_token")
        private val KEY_ON_DEVICE_MODEL = stringPreferencesKey("on_device_model")

        /** Convert raw error strings into plain English for display to users. */
        fun humanizeError(raw: String, providerType: ProviderType? = null): String = when {
            "401" in raw || "Unauthorized" in raw -> when (providerType) {
                ProviderType.OLLAMA -> "Authentication failed. Check your reverse proxy credentials."
                else -> "Invalid API key. Double-check that you copied the full key."
            }

            "403" in raw || "Forbidden" in raw -> when (providerType) {
                ProviderType.OLLAMA -> "Access denied. Check your reverse proxy auth settings."
                else -> "This API key doesn\u2019t have permission. Check your account at the provider\u2019s website."
            }

            "404" in raw && "api/tags" in raw.lowercase() ->
                "Endpoint not found. Verify the server URL points to an Ollama instance."

            "429" in raw || "rate_limit" in raw.lowercase() ->
                "Rate limited \u2014 too many requests. Wait a minute and try again."

            "insufficient_quota" in raw || "billing" in raw.lowercase() ->
                "Your account needs billing set up. Visit the provider\u2019s billing page."

            "ECONNREFUSED" in raw || "ConnectException" in raw || "connect" in raw.lowercase() ->
                "Can\u2019t reach the server. Check the URL and your network connection."

            "timeout" in raw.lowercase() || "SocketTimeoutException" in raw ->
                "Connection timed out. The server may be slow or unreachable."

            "model" in raw.lowercase() && ("not found" in raw.lowercase() || "does not exist" in raw.lowercase()) ->
                "Model not found. It may have been renamed or removed. Try a different model."

            "SSL" in raw || "certificate" in raw.lowercase() ->
                "SSL/certificate error. Check that the server URL uses the correct protocol."

            "Unexpected response format" in raw || "Failed to parse" in raw ->
                "Unexpected response. The URL may point to a proxy login page instead of Ollama."

            else -> "Connection failed: ${raw.take(200)}"
        }
    }
}
