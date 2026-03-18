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
import kotlinx.coroutines.withContext

@Singleton
class LiteRtLmProvider @Inject constructor(
    private val modelManager: LiteRtModelManager,
) : LlmProvider {

    override val name: String = "LiteRT (On-device)"
    override val modelId: String = "gemma3-1b"

    private var engine: Engine? = null

    /**
     * Called by NightlyPredictionWorker during charging window — NOT at unlock time.
     * Engine init can take up to 10 seconds; kept alive as singleton for process lifetime.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (!modelManager.isModelDownloaded()) {
            Log.d(TAG, "Model not downloaded, skipping warm-up")
            return@withContext
        }
        if (engine != null) {
            Log.d(TAG, "Engine already initialized, skipping warm-up")
            return@withContext
        }
        Log.d(TAG, "Warming up LiteRT engine from ${modelManager.modelPath}")
        val config = EngineConfig(
            modelPath = modelManager.modelPath,
            backend = Backend.GPU(),
        )
        val eng = Engine(config)
        eng.initialize()
        engine = eng
        Log.d(TAG, "LiteRT engine warm-up complete")
    }

    fun isReady(): Boolean = engine != null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): LlmResult = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext LlmResult.Error(
            "LiteRT engine not initialized — call warmUp() first",
        )
        try {
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
            )
            eng.createConversation(config).use { conv ->
                val response = conv.sendMessage(messages.last().content)
                LlmResult.Text(response.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT complete() failed", e)
            LlmResult.Error(e.message ?: "LiteRT inference failed", e)
        }
    }

    override fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
    ): Flow<String> = flow {
        val eng = engine ?: error("LiteRT engine not initialized")
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
        )
        eng.createConversation(config).use { conv ->
            conv.sendMessageAsync(messages.last().content)
                .map { message -> message.toString() }
                .collect { emit(it) }
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
