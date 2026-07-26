package com.deepseek.plugin.api

import java.io.IOException

/**
 * 插件统一异常基类。
 *
 * 所有插件内抛出的异常都应继承此类，确保统一处理路径。
 *
 * @property severity 严重级别
 * @property userMessage 展示给用户的友好信息（中文）
 * @property techDetail 记录到日志的技术细节
 */
open class DeepSeekPluginException(
    message: String,
    cause: Throwable? = null,
    val severity: Severity = Severity.ERROR,
    val userMessage: String = message,
    val techDetail: String = ""
) : RuntimeException(message, cause) {

    enum class Severity { WARN, ERROR, FATAL }

    fun toLogString(): String = buildString {
        append("[${severity.name}] $userMessage")
        if (techDetail.isNotBlank()) append(" | $techDetail")
        cause?.let { append(" | Caused by: ${it.message}") }
    }
}

// ============== 具体异常类型 ==============

/**
 * API 调用失败（网络错误、HTTP 错误状态码）。
 */
class ApiException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
    severity: Severity = Severity.ERROR,
    userMessage: String = message
) : DeepSeekPluginException(message, cause, severity, userMessage)

/**
 * API 限流被触发（HTTP 429）。
 */
class RateLimitException(
    message: String = "请求过于频繁，请稍后再试",
    retryAfterMs: Long = 60_000L,
    severity: Severity = Severity.WARN,
    userMessage: String = "请求过于频繁，请稍后再试"
) : DeepSeekPluginException(message, null, severity, userMessage) {
    val retryAfterMs: Long = retryAfterMs
}

/**
 * 会话持久化异常。
 */
class SessionException(
    message: String,
    cause: Throwable? = null,
    severity: Severity = Severity.ERROR,
    userMessage: String = "聊天记录保存失败，请检查磁盘空间"
) : DeepSeekPluginException(message, cause, severity, userMessage)

/**
 * Agent 文件操作异常。
 */
class AgentException(
    message: String,
    cause: Throwable? = null,
    severity: Severity = Severity.ERROR,
    userMessage: String = "Agent 文件操作执行失败"
) : DeepSeekPluginException(message, cause, severity, userMessage)

/**
 * 配置错误（API Key 未设置等）。
 */
class ConfigException(
    message: String,
    severity: Severity = Severity.WARN,
    userMessage: String = message
) : DeepSeekPluginException(message, null, severity, userMessage)

// ============== 扩展 / 工具方法 ==============

/** 将普通 Exception 包装为插件异常的便捷方法 */
fun Throwable.toPluginException(
    userMessage: String = this.message ?: "操作失败",
    severity: DeepSeekPluginException.Severity = DeepSeekPluginException.Severity.ERROR
): DeepSeekPluginException {
    return when (this) {
        is DeepSeekPluginException -> this
        is IOException -> ApiException(
            message = this.message ?: "网络错误",
            cause = this,
            userMessage = userMessage
        )
        else -> DeepSeekPluginException(
            message = this.message ?: userMessage,
            cause = this,
            severity = severity,
            userMessage = userMessage
        )
    }
}
