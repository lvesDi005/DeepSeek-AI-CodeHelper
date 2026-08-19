package com.deepseek.plugin.api

/**
 * Provider 设置快照 — [com.deepseek.plugin.settings.DeepSeekSettings] 中与 Provider 相关的字段的子集。
 * 纯数据对象，无 IntelliJ SDK 依赖，可在 api/backend/frontend 间安全传递。
 */
data class SettingsSnapshot(
    val provider: String,
    val apiKey: String,
    val model: String,
    val agnesApiKey: String,
    val agnesModel: String,
    val agnesBaseUrl: String,
    val nvidiaApiKey: String,
    val nvidiaModel: String,
    val nvidiaBaseUrl: String,
    val openrouterApiKey: String,
    val openrouterModel: String,
    val openrouterBaseUrl: String,
    val zhipuApiKey: String,
    val zhipuModel: String,
    val zhipuBaseUrl: String,
    val anthropicApiKey: String,
    val anthropicModel: String,
    val anthropicBaseUrl: String,
    val codexApiKey: String,
    val codexModel: String,
    val codexBaseUrl: String,
)

/** 根据 Provider 类型从快照中提取 API Key */
fun SettingsSnapshot.resolveApiKey(): String = when (provider) {
    "deepseek" -> apiKey
    "agnes" -> agnesApiKey
    "nvidia" -> nvidiaApiKey
    "openrouter" -> openrouterApiKey
    "zhipu" -> zhipuApiKey
    "anthropic" -> resolveAnthropicApiKey()
    "codex" -> resolveCodexApiKey()
    else -> apiKey
}

/** 根据 Provider 类型从快照中提取 baseUrl */
fun SettingsSnapshot.resolveBaseUrl(): String = when (provider) {
    "deepseek" -> "https://api.deepseek.com/v1"
    "agnes" -> agnesBaseUrl.trimEnd('/')
    "nvidia" -> nvidiaBaseUrl.trimEnd('/')
    "openrouter" -> openrouterBaseUrl.trimEnd('/')
    "zhipu" -> zhipuBaseUrl.trimEnd('/')
    "anthropic" -> {
        val envBase = System.getenv("ANTHROPIC_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) envBase
        else anthropicBaseUrl.trimEnd('/').ifBlank { "https://api.anthropic.com/v1" }
    }
    "codex" -> {
        val envBase = System.getenv("OPENAI_BASE_URL")?.trim()?.trimEnd('/')
        if (!envBase.isNullOrEmpty()) envBase
        else codexBaseUrl.trimEnd('/').ifBlank { "https://api.openai.com/v1" }
    }
    else -> "https://api.deepseek.com/v1"
}

/** 根据 Provider 类型从快照中提取 model */
fun SettingsSnapshot.resolveModel(): String = when (provider) {
    "deepseek" -> model
    "agnes" -> agnesModel.ifBlank { "agnes-2.5-flash" }
    "nvidia" -> nvidiaModel.ifBlank { "z-ai/glm-5.2" }
    "openrouter" -> openrouterModel.ifBlank { "inclusionai/ling-3.0-flash:free" }
    "zhipu" -> zhipuModel.ifBlank { "glm-4" }
    "anthropic" -> anthropicModel.ifBlank { "claude-sonnet-4-5" }
    "codex" -> codexModel.ifBlank { "gpt-5.2-codex" }
    else -> model
}

// ==================== Claude (Anthropic) ====================

/**
 * Claude API key 运行时解析链（与 [LlmProvider] 的 Anthropic 实现保持一致）：
 *  1. 设置面板显式填写的 anthropicApiKey
 *  2. 环境变量 ANTHROPIC_API_KEY（API key 计费）
 *  3. 环境变量 ANTHROPIC_AUTH_TOKEN（Claude 订阅 OAuth 登录态，Bearer 形式）
 */
fun SettingsSnapshot.resolveAnthropicApiKey(): String {
    if (anthropicApiKey.isNotBlank()) return anthropicApiKey
    System.getenv("ANTHROPIC_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv("ANTHROPIC_AUTH_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

/** Claude API key 当前生效来源：settings / env / none */
fun SettingsSnapshot.anthropicKeySource(): String = when {
    anthropicApiKey.isNotBlank() -> "settings"
    !System.getenv("ANTHROPIC_API_KEY").isNullOrBlank() -> "env"
    !System.getenv("ANTHROPIC_AUTH_TOKEN").isNullOrBlank() -> "env"
    else -> "none"
}

// ==================== Codex (OpenAI) ====================

/**
 * Codex API key 运行时解析链（与 [LlmProvider] 的 Codex 实现保持一致）：
 *  1. 设置面板显式填写的 codexApiKey
 *  2. 环境变量 OPENAI_API_KEY（官方 API key）
 *  3. 环境变量 CODEX_API_KEY（cc-switch / 自定义 model_provider 的 env_key 约定）
 */
fun SettingsSnapshot.resolveCodexApiKey(): String {
    if (codexApiKey.isNotBlank()) return codexApiKey
    System.getenv("OPENAI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    System.getenv("CODEX_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return ""
}

/** Codex API key 当前生效来源：settings / env / none */
fun SettingsSnapshot.codexKeySource(): String = when {
    codexApiKey.isNotBlank() -> "settings"
    !System.getenv("OPENAI_API_KEY").isNullOrBlank() -> "env"
    !System.getenv("CODEX_API_KEY").isNullOrBlank() -> "env"
    else -> "none"
}