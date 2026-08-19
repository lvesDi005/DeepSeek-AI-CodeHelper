package com.deepseek.plugin.protocol

import com.deepseek.plugin.api.ChatError
import com.deepseek.plugin.api.ChatMessage
import okhttp3.sse.EventSource

/**
 * Protocol adapter for a specific LLM provider's chat API.
 * Each protocol handles request building, streaming, and error mapping
 * for a single API contract (OpenAI chat/completions, Anthropic Messages, etc.).
 */
interface ChatProtocol {
    /** Unique identifier matching provider protocol: "openai", "anthropic", "codex-responses" */
    val protocolId: String

    fun chatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String? = null
    ): Result<String>

    fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String? = null,
        onDelta: (String) -> Unit,
        onThinking: ((String) -> Unit)? = null,
        onComplete: (String?) -> Unit,
        onError: (ChatError) -> Unit
    ): EventSource

    /** Build the HTTP request body for testing/validation */
    fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String? = null
    ): String
}

/**
 * FIM (Fill-in-the-Middle) completion protocol.
 */
interface FimProtocol {
    fun completionSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        prefix: String,
        suffix: String,
        maxTokens: Int,
        temperature: Double
    ): Result<String>

    fun completionStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        prefix: String,
        suffix: String,
        maxTokens: Int,
        temperature: Double,
        onDelta: (String) -> Unit,
        onComplete: (String?) -> Unit,
        onError: (ChatError) -> Unit
    ): EventSource
}