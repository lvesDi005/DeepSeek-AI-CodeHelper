package com.deepseek.plugin.api

import com.google.gson.annotations.SerializedName

// --- Request models ---

data class ChatRequest(
    val model: String = "deepseek-v4-flash",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false,
    val topP: Double = 1.0,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0
)

data class ChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String
)

// --- Response models ---

data class ChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

data class Choice(
    val index: Int,
    val message: ChatMessage?,
    val delta: Delta?,
    @SerializedName("finish_reason") val finishReason: String?
)

data class Delta(
    val role: String? = null,
    val content: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

// --- FIM Completion models ---

data class FimRequest(
    val model: String = "deepseek-v4-flash",
    val prompt: String,
    val suffix: String? = null,
    @SerializedName("max_tokens") val maxTokens: Int = 256,
    val temperature: Double = 0.0,
    val topP: Double = 0.95,
    val frequencyPenalty: Double = 0.0,
    val presencePenalty: Double = 0.0,
    val stop: List<String>? = null
)

data class FimResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<FimChoice>,
    val usage: Usage?
)

data class FimChoice(
    val index: Int,
    val text: String,
    @SerializedName("finish_reason") val finishReason: String?
)

// --- Streaming SSE chunk ---

data class StreamChunk(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>?,
    val usage: Usage?
)
