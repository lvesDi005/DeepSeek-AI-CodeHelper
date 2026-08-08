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
        register(ZhipuProvider())
        register(AnthropicProvider())
        register(CodexProvider())
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
    override fun model(settings: SettingsSnapshot): String = settings.agnesModel.ifBlank { "agnes-2.5-flash" }
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

/** 智谱 AI GLM */
class ZhipuProvider : LlmProvider {
    override val id = "zhipu"
    override val displayName = "BigModel"

    override fun baseUrl(settings: SettingsSnapshot): String = settings.zhipuBaseUrl.trimEnd('/')
    override fun apiKey(settings: SettingsSnapshot): String = settings.zhipuApiKey
    override fun model(settings: SettingsSnapshot): String = settings.zhipuModel.ifBlank { "glm-4" }
}

/**
 * Claude (Anthropic) — 走 Anthropic 官方 OpenAI 兼容端点（/v1/chat/completions，Bearer 鉴权）。
 *
 * API key 解析链见 [SettingsSnapshot.resolveAnthropicApiKey]（设置面板 → ANTHROPIC_API_KEY 环境变量
 * → ~/.claude/settings.json → ~/.claude/.credentials.json OAuth token，后者仅限官方端点）。
 * baseUrl 优先取环境变量 ANTHROPIC_BASE_URL（企业代理/中转场景）。
 */
class AnthropicProvider : LlmProvider {
    override val id = "anthropic"
    override val displayName = "Claude (Anthropic)"
    override val supportsFim = false
    override val protocol = "anthropic"

    override fun baseUrl(settings: SettingsSnapshot): String {
        val envBase = System.getenv("ANTHROPIC_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) return envBase
        return settings.anthropicBaseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
    }

    override fun apiKey(settings: SettingsSnapshot): String = settings.resolveAnthropicApiKey()

    override fun model(settings: SettingsSnapshot): String =
        settings.anthropicModel.ifBlank { "claude-sonnet-4-5" }
}

/** Claude API key 当前生效来源（委托 [SettingsSnapshot.anthropicKeySource]） */
fun detectAnthropicKeySource(settings: SettingsSnapshot): String = settings.anthropicKeySource()

/**
 * Codex (OpenAI) — 复用本地 Codex CLI 登录态，走 OpenAI 兼容 chat/completions 端点。
 *
 * API key 解析链见 [SettingsSnapshot.resolveCodexApiKey]（设置面板 → OPENAI_API_KEY 环境变量
 * → ~/.codex/auth.json 的 OPENAI_API_KEY）。baseUrl 优先取 OPENAI_BASE_URL 环境变量；
 * model 未设置时自动读取 ~/.codex/config.toml 的 model 配置。
 */
class CodexProvider : LlmProvider {
    override val id = "codex"
    override val displayName = "Codex (OpenAI)"
    override val supportsFim = false

    override fun baseUrl(settings: SettingsSnapshot): String {
        val envBase = System.getenv("OPENAI_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) return envBase
        return settings.codexBaseUrl.trimEnd('/').ifBlank { "https://api.openai.com/v1" }
    }

    override fun apiKey(settings: SettingsSnapshot): String = settings.resolveCodexApiKey()

    override fun model(settings: SettingsSnapshot): String =
        settings.codexModel.ifBlank { readCodexConfigModel() ?: "gpt-5.2-codex" }
}

/** Codex API key 当前生效来源（委托 [SettingsSnapshot.codexKeySource]） */
fun detectCodexKeySource(settings: SettingsSnapshot): String = settings.codexKeySource()

