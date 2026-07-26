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
}
