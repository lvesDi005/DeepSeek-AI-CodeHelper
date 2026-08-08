package com.deepseek.plugin.api

import com.google.gson.JsonParser
import java.io.File

/**
 * Provider 设置快照 — [DeepSeekSettings] 中与 Provider 相关的字段的子集。
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
    "codex" -> codexModel.ifBlank { readCodexConfigModel() ?: "gpt-5.2-codex" }
    else -> model
}

// ==================== Claude (Anthropic) ====================

/**
 * Claude API key 运行时解析链（与 [AnthropicProvider.apiKey] 保持一致）：
 *  1. 设置面板显式填写的 anthropicApiKey
 *  2. 环境变量 ANTHROPIC_API_KEY（API key 计费）
 *  3. 环境变量 ANTHROPIC_AUTH_TOKEN（Claude 订阅 OAuth 登录态，Bearer 形式）
 *
 * 本地文件（~/.claude/settings.json）不再自动读取——由设置页的
 * 「使用本地 settings.json」按钮显式授权后写入设置字段，避免未授权的
 * 凭据被发往 API 端点导致 403。
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

private fun claudeDir(): File = File(System.getProperty("user.home"), ".claude")

/**
 * 读取 ~/.claude/settings.json 的 env 中的 Claude 凭据（供「使用本地 settings.json」按钮调用）。
 * 优先 env.ANTHROPIC_API_KEY，其次 env.ANTHROPIC_AUTH_TOKEN。
 */
fun readClaudeSettingsEnvKey(): String? {
    return try {
        val f = File(claudeDir(), "settings.json")
        if (!f.isFile) return null
        val env = JsonParser.parseReader(f.reader()).asJsonObject.getAsJsonObject("env") ?: return null
        env.get("ANTHROPIC_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: env.get("ANTHROPIC_AUTH_TOKEN")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/** 读取 ~/.claude/settings.json 的 env.ANTHROPIC_BASE_URL（供按钮调用，可为 null） */
fun readClaudeSettingsBaseUrl(): String? {
    return try {
        val f = File(claudeDir(), "settings.json")
        if (!f.isFile) return null
        val json = JsonParser.parseReader(f.reader()).asJsonObject
        json.getAsJsonObject("env")?.get("ANTHROPIC_BASE_URL")
            ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/**
 * 读取 ~/.claude/settings.json env 中的 Claude 模型映射（cc-switch / Claude Code 约定）：
 *  ANTHROPIC_DEFAULT_SONNET_MODEL / ANTHROPIC_DEFAULT_HAIKU_MODEL / ANTHROPIC_DEFAULT_OPUS_MODEL。
 * 返回 { "sonnet" -> "...", "haiku" -> "...", "opus" -> "..." }，缺失的键不包含。
 */
fun readClaudeModelMapping(): Map<String, String> {
    return try {
        val f = File(claudeDir(), "settings.json")
        if (!f.isFile) return emptyMap()
        val env = JsonParser.parseReader(f.reader()).asJsonObject.getAsJsonObject("env") ?: return emptyMap()
        fun get(key: String): String? =
            env.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        buildMap {
            get("ANTHROPIC_DEFAULT_SONNET_MODEL")?.let { put("sonnet", it) }
            get("ANTHROPIC_DEFAULT_HAIKU_MODEL")?.let { put("haiku", it) }
            get("ANTHROPIC_DEFAULT_OPUS_MODEL")?.let { put("opus", it) }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

// ==================== Codex (OpenAI) ====================

/**
 * Codex API key 运行时解析链（与 [CodexProvider.apiKey] 保持一致）：
 *  1. 设置面板显式填写的 codexApiKey
 *  2. 环境变量 OPENAI_API_KEY（官方 API key）
 *  3. 环境变量 CODEX_API_KEY（cc-switch / 自定义 model_provider 的 env_key 约定）
 *
 * ~/.codex/auth.json 不再自动读取——由设置页的「使用本地配置信息」按钮
 * 显式授权后写入设置字段。Codex CLI 的 OAuth token（ChatGPT 账号登录）
 * 无法用于 api.openai.com 的 chat/completions，因此不会作为 key 使用。
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

private fun codexDir(): File = File(System.getProperty("user.home"), ".codex")

/**
 * 读取 ~/.codex/auth.json 的 API key（供「使用本地配置信息」按钮调用）。
 * 优先 OPENAI_API_KEY（官方 codex login），其次 CODEX_API_KEY（cc-switch / 自定义 model_provider 约定）。
 */
fun readCodexAuthApiKey(): String? {
    return try {
        val f = File(codexDir(), "auth.json")
        if (!f.isFile) return null
        val json = JsonParser.parseReader(f.reader()).asJsonObject
        json.get("OPENAI_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            ?: json.get("CODEX_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/** 检测 ~/.codex/auth.json 是否仅包含 OAuth 登录态（无可用 API key，供按钮提示） */
fun readCodexAuthHasOAuthOnly(): Boolean {
    return try {
        val f = File(codexDir(), "auth.json")
        if (!f.isFile) return false
        val json = JsonParser.parseReader(f.reader()).asJsonObject
        val apiKey = json.get("OPENAI_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        val codexKey = json.get("CODEX_API_KEY")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
        apiKey.isNullOrEmpty() && codexKey.isNullOrEmpty() && json.get("tokens")?.isJsonObject == true
    } catch (e: Exception) {
        false
    }
}

/** 读取 ~/.codex/config.toml 的 model = "..."（Codex CLI 当前使用的模型，供按钮/默认值调用） */
fun readCodexConfigModel(): String? {
    return try {
        val f = File(codexDir(), "config.toml")
        if (!f.isFile) return null
        Regex("""(?m)^\s*model\s*=\s*["']([^"']+)["']""").find(f.readText())
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}

/**
 * 读取 ~/.codex/config.toml 当前选中 model_provider 段的 base_url（cc-switch 代理场景）。
 * 例如 [model_providers.中转名] 内的 base_url = "https://..."。
 */
fun readCodexConfigBaseUrl(): String? {
    return try {
        val f = File(codexDir(), "config.toml")
        if (!f.isFile) return null
        val text = f.readText()
        val providerName = Regex("""(?m)^\s*model_provider\s*=\s*["']([^"']+)["']""").find(text)
            ?.groupValues?.get(1) ?: return null
        val section = Regex("""(?ms)\[model_providers\.${Regex.escape(providerName)}]\s*\n(.*?)(?=\n\[|$)""")
            .find(text)?.groupValues?.get(1) ?: return null
        Regex("""(?m)^\s*base_url\s*=\s*["']([^"']+)["']""").find(section)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
