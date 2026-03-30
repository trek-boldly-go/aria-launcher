// Copyright (c) 2026 Donovon Simpson. All rights reserved. See LICENSE-ARIA.md
package com.aria.launcher.aria.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class LiteRtLmProvider @Inject constructor(
    private val modelManager: LiteRtModelManager,
) : LlmProvider {

    override val name: String get() = "LiteRT (${modelManager.selectedModel.displayName})"
    override val modelId: String get() = modelManager.selectedModel.modelIdTag

    @Volatile private var engine: Engine? = null

    @Volatile private var activeBackend: String = "GPU"
    private val warmUpMutex = Mutex()

    /** LiteRT only supports one conversation at a time — serialize all inference calls. */
    private val sessionMutex = Mutex()

    /**
     * Initializes the LiteRT engine. Safe to call from multiple coroutines —
     * the mutex ensures only one initialization runs.
     * Engine init takes ~10s; kept alive as singleton for process lifetime.
     *
     * Tries GPU first; if the model's ops aren't supported on this device's GPU
     * (e.g. Gemma 3n on Tensor G4), falls back to CPU automatically.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!modelManager.isModelDownloaded()) {
            Log.d(TAG, "Model not downloaded, skipping warm-up")
            return@withContext
        }
        if (engine != null) return@withContext
        warmUpMutex.withLock {
            // Double-check after acquiring lock
            if (engine != null) return@withLock
            Log.d(TAG, "Warming up LiteRT engine from ${modelManager.modelPath}")
            engine = tryCreateEngine(Backend.GPU(), "GPU")
                ?: tryCreateEngine(Backend.CPU(), "CPU")
            if (engine == null) {
                Log.e(TAG, "Failed to initialize engine on both GPU and CPU")
            }
        }
    }

    private fun tryCreateEngine(backend: Backend, label: String): Engine? {
        return try {
            Log.d(TAG, "Trying $label backend for ${modelManager.selectedModel.displayName}")
            val config = EngineConfig(
                modelPath = modelManager.modelPath,
                backend = backend,
            )
            val eng = Engine(config)
            eng.initialize()
            activeBackend = label
            Log.d(TAG, "LiteRT engine warm-up complete ($label backend)")
            eng
        } catch (e: Exception) {
            Log.w(TAG, "$label backend failed: ${e.message}")
            null
        }
    }

    fun isReady(): Boolean = engine != null

    /**
     * Tears down the current engine to free GPU resources.
     * Called when switching between on-device model variants.
     * Next inference call will re-initialize via [ensureEngine].
     */
    suspend fun invalidateEngine() {
        warmUpMutex.withLock {
            val old = engine
            engine = null
            // Engine doesn't implement Closeable, but nulling lets GC reclaim GPU resources
            Log.d(TAG, "Engine invalidated (was ${if (old != null) "active" else "null"})")
        }
    }

    /**
     * Lazily initializes the engine on first use. Returns null if model isn't downloaded.
     * First call takes ~10s for engine init; subsequent calls return instantly.
     */
    private suspend fun ensureEngine(): Engine? {
        engine?.let { return it }
        if (!modelManager.isModelDownloaded()) return null
        warmUp()
        return engine
    }

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val eng = ensureEngine() ?: return@withContext LlmResult.Error(
            "On-device model not downloaded. Download it in ARIA settings.",
        )
        sessionMutex.withLock {
            try {
                val userMessage = messages.last().content
                Log.d(TAG, "complete() ── INPUT ──")
                Log.d(TAG, "  system: ${systemPrompt.take(500)}")
                Log.d(TAG, "  user: ${userMessage.take(500)}")
                Log.d(TAG, "  messages: ${messages.size}, maxTokens: $maxTokens")
                val startMs = System.currentTimeMillis()
                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                )
                eng.createConversation(config).use { conv ->
                    val response = conv.sendMessage(userMessage)
                    val output = response.toString()
                    val elapsedMs = System.currentTimeMillis() - startMs
                    Log.d(TAG, "complete() ── OUTPUT ($elapsedMs ms) ──")
                    Log.d(TAG, "  response: ${output.take(1000)}")
                    LlmResult.Text(output)
                }
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT complete() failed", e)
                LlmResult.Error(e.message ?: "LiteRT inference failed", e)
            }
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = flow {
        val eng = ensureEngine() ?: error("On-device model not downloaded")
        sessionMutex.withLock {
            val userMessage = messages.last().content
            Log.d(TAG, "streamComplete() ── INPUT ──")
            Log.d(TAG, "  system: ${systemPrompt.take(500)}")
            Log.d(TAG, "  user: ${userMessage.take(500)}")
            Log.d(TAG, "  messages: ${messages.size}, maxTokens: $maxTokens")
            val startMs = System.currentTimeMillis()
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
            )
            val fullResponse = StringBuilder()
            eng.createConversation(config).use { conv ->
                conv.sendMessageAsync(userMessage)
                    .map { message -> message.toString() }
                    .collect { chunk ->
                        fullResponse.append(chunk)
                        emit(chunk)
                    }
            }
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.d(TAG, "streamComplete() ── OUTPUT ($elapsedMs ms, ${fullResponse.length} chars) ──")
            Log.d(TAG, "  response: ${fullResponse.toString().take(1000)}")
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
    ): LlmResult {
        // LiteRT-LM supports @Tool annotations and OpenAPI specs for native tool use.
        // For now, embed tool definitions in the system prompt and delegate to complete().
        // Native tool use integration deferred to a future session.
        return complete(systemPrompt, messages, maxTokens)
    }

    companion object {
        private const val TAG = "ARIA.LiteRtLm"
    }
}
