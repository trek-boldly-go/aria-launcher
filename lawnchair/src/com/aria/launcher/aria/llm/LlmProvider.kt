package com.aria.launcher.aria.llm

import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    val name: String
    val modelId: String

    suspend fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1024,
        responseFormat: ResponseFormat = ResponseFormat.None,
    ): LlmResult

    fun streamComplete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1024,
    ): Flow<String>

    suspend fun completeWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        maxTokens: Int = 1024,
        responseFormat: ResponseFormat = ResponseFormat.None,
    ): LlmResult
}
