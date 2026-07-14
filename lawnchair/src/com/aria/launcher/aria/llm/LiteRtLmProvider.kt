// Copyright (c) 2026 Donovon Simpson. See LICENSE-ARIA.md for licensing terms.
package com.aria.launcher.aria.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import java.util.UUID
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
                val initialMessages = messages.dropLast(1).map(::toLiteRtMessage)
                val userMessage = messages.lastOrNull()?.let(LiteRtToolPrompt::contentForMessage) ?: ""
                Log.d(TAG, "complete() ── INPUT ──")
                Log.d(TAG, "  system: ${systemPrompt.take(500)}")
                Log.d(TAG, "  user: ${userMessage.take(500)}")
                Log.d(TAG, "  messages: ${messages.size} (history: ${initialMessages.size}), maxTokens: $maxTokens")
                val startMs = System.currentTimeMillis()
                val config = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    initialMessages = initialMessages,
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
            val initialMessages = messages.dropLast(1).map(::toLiteRtMessage)
            val userMessage = messages.lastOrNull()?.let(LiteRtToolPrompt::contentForMessage) ?: ""
            Log.d(TAG, "streamComplete() ── INPUT ──")
            Log.d(TAG, "  system: ${systemPrompt.take(500)}")
            Log.d(TAG, "  user: ${userMessage.take(500)}")
            Log.d(TAG, "  messages: ${messages.size} (history: ${initialMessages.size}), maxTokens: $maxTokens")
            val startMs = System.currentTimeMillis()
            val config = ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = initialMessages,
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

    /**
     * On-device models have no native tool-calling, so we prompt for it: the tool
     * catalog is appended to the system prompt and the model is asked to reply with a
     * single JSON object when it wants to call a tool. The reply is parsed back into a
     * [LlmResult.ToolUse]; anything else is plain text. The tool list is capped for
     * on-device — Gemma-3n-class models cannot juggle the full ~20-tool set.
     */
    override suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int,
    ): LlmResult {
        if (tools.isEmpty()) return complete(systemPrompt, messages, maxTokens)
        val cappedTools = tools.take(MAX_ON_DEVICE_TOOLS)
        val augmentedSystem = systemPrompt + "\n\n" + LiteRtToolPrompt.renderToolInstructions(cappedTools)
        return when (val result = complete(augmentedSystem, messages, maxTokens)) {
            is LlmResult.Text -> when (val reply = LiteRtToolPrompt.parseReply(result.content)) {
                is LiteRtToolPrompt.Reply.Invocation -> LlmResult.ToolUse(
                    content = "",
                    toolCalls = listOf(ToolCall(UUID.randomUUID().toString(), reply.name, reply.arguments)),
                )

                is LiteRtToolPrompt.Reply.PlainText -> LlmResult.Text(reply.text)
            }

            else -> result
        }
    }

    /** Maps an ARIA [ChatMessage] into a LiteRT [Message] for history replay. */
    private fun toLiteRtMessage(msg: ChatMessage): Message {
        val text = LiteRtToolPrompt.contentForMessage(msg)
        return when (msg.role) {
            // Tool results are folded into user-turn text by contentForMessage; on-device
            // models handle a dedicated TOOL role inconsistently.
            Role.USER, Role.TOOL -> Message.user(text)

            Role.ASSISTANT -> Message.model(Contents.of(text))

            Role.SYSTEM -> Message.system(text)
        }
    }

    companion object {
        private const val TAG = "ARIA.LiteRtLm"

        /** Small on-device models degrade sharply past ~8 tools; keep the catalog tight. */
        private const val MAX_ON_DEVICE_TOOLS = 8
    }
}
