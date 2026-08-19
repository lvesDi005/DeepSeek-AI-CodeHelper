package com.deepseek.plugin.protocol

import com.deepseek.plugin.api.ChatError
import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.ChatRequest
import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.toChatError
import com.google.gson.Gson
import okhttp3.sse.EventSource

/**
 * OpenAI chat/completions protocol adapter.
 */
class OpenAiChatProtocolAdapter(
    private val client: DeepSeekApiClient = DeepSeekApiClient()
) : ChatProtocol {
    override val protocolId = "openai"

    override fun chatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?
    ): Result<String> = client.chatSyncWithExplicitConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        messages = withSystem(messages, systemPrompt),
        protocol = protocolId
    )

    override fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?,
        onDelta: (String) -> Unit,
        onThinking: ((String) -> Unit)?,
        onComplete: (String?) -> Unit,
        onError: (ChatError) -> Unit
    ): EventSource = client.chatStreamWithExplicitConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        messages = withSystem(messages, systemPrompt),
        protocol = protocolId,
        onToken = onDelta,
        onComplete = { full, _ -> onComplete(full) },
        onError = { onError(it.toChatError()) },
        onReasoningToken = onThinking
    )

    override fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?
    ): String = gson.toJson(
        ChatRequest(
            model = model,
            messages = withSystem(messages, systemPrompt),
            temperature = temperature,
            maxTokens = maxTokens,
            stream = true
        )
    )

    private companion object {
        val gson = Gson()
    }
}

/**
 * Anthropic native Messages API adapter.
 */
class AnthropicChatProtocolAdapter(
    private val client: DeepSeekApiClient = DeepSeekApiClient()
) : ChatProtocol {
    override val protocolId = "anthropic"

    override fun chatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?
    ): Result<String> = client.chatSyncWithExplicitConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        messages = withSystem(messages, systemPrompt),
        protocol = protocolId
    )

    override fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?,
        onDelta: (String) -> Unit,
        onThinking: ((String) -> Unit)?,
        onComplete: (String?) -> Unit,
        onError: (ChatError) -> Unit
    ): EventSource = client.chatStreamWithExplicitConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        messages = withSystem(messages, systemPrompt),
        protocol = protocolId,
        onToken = onDelta,
        onComplete = { full, _ -> onComplete(full) },
        onError = { onError(it.toChatError()) },
        onReasoningToken = onThinking
    )

    override fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?
    ): String = client.buildAnthropicBody(
        model = model,
        messages = withSystem(messages, systemPrompt),
        temperature = temperature,
        maxTokens = maxTokens,
        stream = true
    )
}

/**
 * OpenAI Responses API adapter used by Codex and cc-switch-compatible providers.
 */
class CodexResponsesChatProtocolAdapter(
    private val client: DeepSeekApiClient = DeepSeekApiClient()
) : ChatProtocol {
    override val protocolId = "codex-responses"

    override fun chatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?
    ): Result<String> = client.chatSyncWithExplicitConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        messages = withSystem(messages, systemPrompt),
        protocol = protocolId
    )

    override fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?,
        onDelta: (String) -> Unit,
        onThinking: ((String) -> Unit)?,
        onComplete: (String?) -> Unit,
        onError: (ChatError) -> Unit
    ): EventSource = client.chatStreamWithExplicitConfig(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        temperature = temperature,
        maxTokens = maxTokens,
        messages = withSystem(messages, systemPrompt),
        protocol = protocolId,
        onToken = onDelta,
        onComplete = { full, _ -> onComplete(full) },
        onError = { onError(it.toChatError()) },
        onReasoningToken = onThinking
    )

    override fun buildRequestBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        systemPrompt: String?
    ): String = client.buildCodexResponsesBody(
        model = model,
        messages = withSystem(messages, systemPrompt),
        temperature = temperature,
        maxTokens = maxTokens,
        stream = true
    )
}

/** Prepends a system prompt to a message list without mutating the input. */
private fun withSystem(messages: List<ChatMessage>, systemPrompt: String?): List<ChatMessage> =
    if (systemPrompt.isNullOrBlank()) messages else listOf(ChatMessage("system", systemPrompt)) + messages

object ChatProtocolRegistry {
    private val adapters: Map<String, ChatProtocol> = mapOf(
        "openai" to OpenAiChatProtocolAdapter(),
        "anthropic" to AnthropicChatProtocolAdapter(),
        "codex-responses" to CodexResponsesChatProtocolAdapter()
    )

    fun get(protocol: String): ChatProtocol =
        adapters[protocol] ?: adapters.getValue("openai")
}