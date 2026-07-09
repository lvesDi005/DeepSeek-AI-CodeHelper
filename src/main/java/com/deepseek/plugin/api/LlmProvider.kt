package com.deepseek.plugin.api

import com.deepseek.plugin.settings.DeepSeekSettings

/**
 * LLM 供应商策略接口。
 *
 * 新供应商只需实现此接口并注册到 [LlmProviderRegistry]，无需修改 [DeepSeekApiClient]。
 */
interface LlmProvider {
    /** 唯一标识符，对应 settings.provider 的值 */
    val id: String
    /** 设置界面显示名 */
    val displayName: String

    /** 供应商 API base URL */
    fun baseUrl(settings: DeepSeekSettings): String

    /** 供应商 API Key */
    fun apiKey(settings: DeepSeekSettings): String

    /** 当前使用的模型名 */
    fun model(settings: DeepSeekSettings): String

    /** 是否支持 FIM 代码补全 */
    val supportsFim: Boolean get() = false
}

// ============== 实现 ==============

/** DeepSeek 官方 API */
class DeepSeekProvider : LlmProvider {
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val supportsFim = true

    override fun baseUrl(settings: DeepSeekSettings): String = "https://api.deepseek.com/v1"
    override fun apiKey(settings: DeepSeekSettings): String = settings.apiKey
    override fun model(settings: DeepSeekSettings): String = settings.model
}

/** Agnes 2.0 Flash */
class AgnesProvider : LlmProvider {
    override val id = "agnes"
    override val displayName = "Agnes 2.0 Flash"

    override fun baseUrl(settings: DeepSeekSettings): String = settings.agnesBaseUrl.trimEnd('/')
    override fun apiKey(settings: DeepSeekSettings): String = settings.agnesApiKey
    override fun model(settings: DeepSeekSettings): String = settings.agnesModel.ifBlank { "agnes-2.0-flash" }
}

/** NVIDIA NIM API */
class NvidiaProvider : LlmProvider {
    override val id = "nvidia"
    override val displayName = "NVIDIA"

    override fun baseUrl(settings: DeepSeekSettings): String = settings.nvidiaBaseUrl.trimEnd('/')
    override fun apiKey(settings: DeepSeekSettings): String = settings.nvidiaApiKey
    override fun model(settings: DeepSeekSettings): String = settings.nvidiaModel.ifBlank { "z-ai/glm-5.2" }
}

/** OpenRouter API — 聚合多模型 */
class OpenRouterProvider : LlmProvider {
    override val id = "openrouter"
    override val displayName = "OpenRouter"

    override fun baseUrl(settings: DeepSeekSettings): String = settings.openrouterBaseUrl.trimEnd('/')
    override fun apiKey(settings: DeepSeekSettings): String = settings.openrouterApiKey
    override fun model(settings: DeepSeekSettings): String = settings.openrouterModel.ifBlank { "poolside/laguna-xs-2.1:free" }
}

// ============== 注册表 ==============

/**
 * 供应商注册表。
 *
 * 添加新供应商：编写 `class XxxProvider : LlmProvider`，然后一行：
 *   `LlmProviderRegistry.register(XxxProvider())`
 */
object LlmProviderRegistry {
    private val providers = mutableMapOf<String, LlmProvider>()

    init {
        register(DeepSeekProvider())
        register(AgnesProvider())
        register(NvidiaProvider())
        register(OpenRouterProvider())
    }

    fun register(provider: LlmProvider) {
        providers[provider.id] = provider
    }

    fun get(id: String): LlmProvider =
        providers[id] ?: providers["deepseek"]
            ?: throw IllegalStateException("No default provider registered")

    fun allProviders(): Collection<LlmProvider> = providers.values

    fun hasProvider(id: String): Boolean = id in providers
}
