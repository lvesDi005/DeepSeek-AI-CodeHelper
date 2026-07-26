package com.deepseek.plugin.api

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
)

/** 根据 Provider 类型从快照中提取 API Key */
fun SettingsSnapshot.resolveApiKey(): String = when (provider) {
    "deepseek" -> apiKey
    "agnes" -> agnesApiKey
    "nvidia" -> nvidiaApiKey
    "openrouter" -> openrouterApiKey
    else -> apiKey
}

/** 根据 Provider 类型从快照中提取 baseUrl */
fun SettingsSnapshot.resolveBaseUrl(): String = when (provider) {
    "deepseek" -> "https://api.deepseek.com/v1"
    "agnes" -> agnesBaseUrl.trimEnd('/')
    "nvidia" -> nvidiaBaseUrl.trimEnd('/')
    "openrouter" -> openrouterBaseUrl.trimEnd('/')
    else -> "https://api.deepseek.com/v1"
}

/** 根据 Provider 类型从快照中提取 model */
fun SettingsSnapshot.resolveModel(): String = when (provider) {
    "deepseek" -> model
    "agnes" -> agnesModel.ifBlank { "agnes-2.0-flash" }
    "nvidia" -> nvidiaModel.ifBlank { "z-ai/glm-5.2" }
    "openrouter" -> openrouterModel.ifBlank { "inclusionai/ling-3.0-flash:free" }
    else -> model
}
