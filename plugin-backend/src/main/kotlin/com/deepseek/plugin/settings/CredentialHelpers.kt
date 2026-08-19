package com.deepseek.plugin.settings

import com.google.gson.JsonParser
import java.io.File

// ==================== Claude (Anthropic) ====================

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

private fun codexDir(): File =
    System.getenv("CODEX_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)
        ?: File(System.getProperty("user.home"), ".codex")

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
        val sectionHeader = Regex("""^\s*\[model_providers\.${Regex.escape(providerName)}\]\s*$""")
        val baseUrl = Regex("""^\s*base_url\s*=\s*["']([^"']+)["']\s*(?:#.*)?$""")
        var inProviderSection = false
        for (line in text.lineSequence()) {
            if (line.trimStart().startsWith("[") && !sectionHeader.matches(line.trim())) {
                if (inProviderSection) break
                continue
            }
            if (sectionHeader.matches(line.trim())) {
                inProviderSection = true
                continue
            }
            if (inProviderSection) {
                baseUrl.find(line.trim())?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}