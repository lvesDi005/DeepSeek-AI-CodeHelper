package com.deepseek.plugin.api

/**
 * 供应商注册表 — 管理所有 LLM 供应商实例。
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

// ============== 实现 ==============

/** DeepSeek 官方 API */
class DeepSeekProvider : LlmProvider {
    override val id = "deepseek"
    override val displayName = "DeepSeek"
    override val supportsFim = true

    override fun baseUrl(settings: SettingsSnapshot): String = "https://api.deepseek.com/v1"
    override fun apiKey(settings: SettingsSnapshot): String = settings.apiKey
    override fun model(settings: SettingsSnapshot): String = settings.model
}

/** Agnes 2.0 Flash */
class AgnesProvider : LlmProvider {
    override val id = "agnes"
    override val displayName = "Agnes 2.0 Flash"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.agnesBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.agnesApiKey
    override fun model(settings: SettingsSnapshot): String = settings.agnesModel.ifBlank { "agnes-2.0-flash" }
}

/** NVIDIA NIM API */
class NvidiaProvider : LlmProvider {
    override val id = "nvidia"
    override val displayName = "NVIDIA"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.nvidiaBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.nvidiaApiKey
    override fun model(settings: SettingsSnapshot): String = settings.nvidiaModel.ifBlank { "z-ai/glm-5.2" }
}

/** OpenRouter API — 聚合多模型 */
class OpenRouterProvider : LlmProvider {
    override val id = "openrouter"
    override val displayName = "OpenRouter"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.openrouterBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.openrouterApiKey
    override fun model(settings: SettingsSnapshot): String = settings.openrouterModel.ifBlank { "inclusionai/ling-3.0-flash:free" }
}
