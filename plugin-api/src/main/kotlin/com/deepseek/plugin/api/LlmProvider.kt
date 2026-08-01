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

    /**
     * 固定的 temperature 值。当非 null 时，覆盖用户在设置中的配置。
     * 用于某些模型只接受特定 temperature（如 Kimi K3 仅接受 1.0）。
     */
    val temperature: Double? get() = null
}
