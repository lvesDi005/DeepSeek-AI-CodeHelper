package com.deepseek.plugin.api

/**
 * LLM 供应商策略接口。
 *
 * 新供应商只需实现此接口并注册到 [LlmProviderRegistry]。
 */
interface LlmProvider {
    /** 唯一标识符，对应 settings.provider 的值 */
    val id: String
    /** 设置界面显示名 */
    val displayName: String

    /** 供应商 API base URL */
    fun baseUrl(settings: SettingsSnapshot): String

    /** 供应商 API Key */
    fun apiKey(settings: SettingsSnapshot): String

    /** 当前使用的模型名 */
    fun model(settings: SettingsSnapshot): String

    /** 是否支持 FIM 代码补全 */
    val supportsFim: Boolean get() = false

    /** Capabilities used by Q&A routing instead of protocol-name checks. */
    val capabilities: LlmCapabilities get() = LlmCapabilities()

    /**
     * 聊天请求协议：
     *  - "openai"（默认）：POST {baseUrl}/chat/completions，Authorization: Bearer，OpenAI 格式 SSE
     *  - "anthropic"：POST {baseUrl}/v1/messages，x-api-key + anthropic-version，Anthropic 原生 SSE
     *    （用于对接 cc-switch 等第三方中转的 Anthropic 兼容端点，如 https://api.deepseek.com/anthropic）
     *  - "codex-responses"：POST {baseUrl}/responses，Authorization: Bearer，OpenAI Responses API 原生 SSE
     *    （用于 Codex CLI gpt-5.x-codex 等仅支持 Responses API 的模型，以及 cc-switch 直连模式）
     */
    val protocol: String get() = "openai"

    /**
     * 固定的 temperature 值。当非 null 时，覆盖用户在设置中的配置。
     * 用于某些模型只接受特定 temperature（如 Kimi K3 仅接受 1.0）。
     */
    val temperature: Double? get() = null
}

data class LlmCapabilities(
    val nativeImages: Boolean = false,
    val nativeReasoning: Boolean = false,
    val readOnlyTools: Boolean = false,
    val conversationContinuation: Boolean = false,
    val promptCaching: Boolean = false,
    /** Conservative input budget used until a model-specific catalog overrides it. */
    val maxContextTokens: Int = 32_000
)
