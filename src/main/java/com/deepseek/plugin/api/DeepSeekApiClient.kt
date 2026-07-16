package com.deepseek.plugin.api

import com.deepseek.plugin.settings.DeepSeekSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

class DeepSeekApiClient {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()

        /** 最大重试次数 */
        private const val MAX_RETRIES = 2
        /** 初始退避延迟 (ms) */
        private const val BASE_DELAY_MS = 500L

        /**
         * 判断错误是否可重试：
         * - IOException（网络层故障，如连接重置、DNS 超时）
         * - HTTP 429（限流）
         * - HTTP 5xx（服务端错误）
         */
        private fun isRetryable(error: Throwable, httpCode: Int?): Boolean {
            if (error is IOException) return true
            if (error is RateLimitException) return true
            return httpCode != null && httpCode >= 500
        }
    }

    /**
     * 带指数退避重试的执行器。
     * 只对 [isRetryable] 判定为可重试的错误进行重试。
     */
    private fun <T> retryWithBackoff(
        maxRetries: Int = MAX_RETRIES,
        block: () -> Result<T>
    ): Result<T> {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            val result = block()
            result.fold(
                onSuccess = { return result },
                onFailure = { error ->
                    lastError = error
                    val httpCode = extractHttpCode(error)
                    if (attempt < maxRetries - 1 && isRetryable(error, httpCode)) {
                        val delayMs = BASE_DELAY_MS * (1L shl attempt) // 1s, 2s, 4s
                        try {
                            Thread.sleep(delayMs)
                        } catch (_: InterruptedException) {
                            return Result.failure(error)
                        }
                    } else {
                        return result // 不可重试或已达最大次数
                    }
                }
            )
        }
        return Result.failure(lastError ?: IOException("Retry exhausted"))
    }

    /** 从异常中尝试提取 HTTP 状态码 */
    private fun extractHttpCode(error: Throwable): Int? {
        val msg = error.message ?: return null
        val match = Regex("HTTP (\\d+)").find(msg)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // ── Provider 辅助 ──
    private val syncClient get() = HttpClientProvider.chatSyncClient
    /** 流式 SSE 调用使用宽松超时的共享客户端 */
    private val streamClient get() = HttpClientProvider.SHARED_CLIENT
    /** FIM 补全使用短超时客户端 */
    private val completionClient get() = HttpClientProvider.completionClient

    // ── Provider 辅助（策略模式）──
    private fun provider(settings: DeepSeekSettings): LlmProvider =
        LlmProviderRegistry.get(settings.provider)

    // ============ 非流式调用 (Agent Actions) ============

    fun chatSync(systemPrompt: String, userMessage: String): Result<String> {
        val settings = DeepSeekSettings.instance
        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userMessage)
        )
        return chatSync(settings, messages)
    }

    fun chatSync(messages: List<ChatMessage>): Result<String> {
        return chatSync(DeepSeekSettings.instance, messages)
    }

    private fun chatSync(settings: DeepSeekSettings, messages: List<ChatMessage>): Result<String> {
        return chatSyncWithExplicitConfig(
            baseUrl = provider(settings).baseUrl(settings),
            apiKey = provider(settings).apiKey(settings),
            model = provider(settings).model(settings),
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            messages = messages
        )
    }

    /**
     * 同步调用 —— 使用显式指定的 API 配置（用于多 Agent 分工）。
     */
    fun chatSyncWithExplicitConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double = 0.7,
        maxTokens: Int = 4096,
        messages: List<ChatMessage>
    ): Result<String> {
        // H3: 请求限流检查
        if (!HttpClientProvider.chatRateLimiter.tryAcquire()) {
            return Result.failure(RateLimitException())
        }
        return retryWithBackoff {
            val request = ChatRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                stream = false
            )

            val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

            val httpRequest = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            try {
                syncClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = tryParseError(responseBody, response.code)
                        val ex = if (response.code == 429) {
                            RateLimitException()
                        } else {
                            ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                        }
                        Result.failure(ex)
                    } else {
                        val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                        val content = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                        Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    // ============ 流式调用 (Chat Panel) ============

    /**
     * 流式调用 —— 使用 settings 中的默认配置（模型/Provider）。
     * 保留现有接口不变。
     */
    fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val settings = DeepSeekSettings.instance
        return chatStreamWithExplicitConfig(
            baseUrl = provider(settings).baseUrl(settings),
            apiKey = provider(settings).apiKey(settings),
            model = provider(settings).model(settings),
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            messages = messages,
            onToken = onToken,
            onComplete = onComplete,
            onError = onError
        )
    }

    /**
     * 流式调用 —— 使用显式指定的 API 配置（用于多 Agent 分工，每个 Agent 使用不同模型/Provider）。
     *
     * @param baseUrl  API base URL（如 "https://api.deepseek.com/v1"）
     * @param apiKey   API Key
     * @param model    模型名（如 "deepseek-v4-pro"、"deepseek-v4-flash"、"agnes-2.0-flash"）
     * @param temperature 温度
     * @param maxTokens 最大 Token 数
     */
    fun chatStreamWithExplicitConfig(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double = 0.7,
        maxTokens: Int = 4096,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        // 请求限流检查
        if (!HttpClientProvider.chatRateLimiter.tryAcquire()) {
            onError(RateLimitException())
            return object : EventSource {
                override fun cancel() {}
                override fun request() = Request.Builder().url("$baseUrl/chat/completions").build()
            }
        }
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            stream = true
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val factory = EventSources.createFactory(streamClient)
        val fullResponse = StringBuilder()
        var lastUsage: Usage? = null
        var completed = false

        return factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (!completed) {
                        completed = true
                        onComplete(fullResponse.toString(), lastUsage)
                    }
                    return
                }
                try {
                    val chunk = gson.fromJson(data, StreamChunk::class.java)
                    val delta = chunk?.choices?.firstOrNull()?.delta
                    val content = delta?.content ?: ""
                    if (content.isNotEmpty()) {
                        fullResponse.append(content)
                        onToken(content)
                    }
                    chunk?.usage?.let { lastUsage = it }
                } catch (_: Exception) {}
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (completed) return
                completed = true
                val bodyStr = response?.body?.string() ?: ""
                val errMsg = if (response != null) {
                    "API error ${response.code}: ${tryParseError(bodyStr, response.code)}"
                } else {
                    t?.message ?: "Unknown error"
                }
                onError(ApiException(errMsg, httpCode = response?.code))
            }

            override fun onClosed(eventSource: EventSource) {
                if (!completed) {
                    completed = true
                    onComplete(fullResponse.toString(), lastUsage)
                }
            }
        })
    }

    // ============ FIM Completion (代码补全) ============

    /**
     * FIM (Fill-in-the-Middle) 代码补全 —— 使用 DeepSeek /v1/completions FIM 端点.
     * 异步回调模式,供 CompletionContributor 使用.
     *
     * @param prefix 光标前文本
     * @param suffix 光标后文本
     * @param language 编程语言名
     * @param fileContext 文件级上下文（包声明、imports）
     * @param mode 触发模式 — AUTO 走低 temperature+短输出, MANUAL 走高 temperature+长输出
     * @param onResult 回调函数（EDT 外调用, 返回补全文本或 null）
     */
    fun completionFim(
        prefix: String,
        suffix: String,
        language: String,
        fileContext: String,
        mode: com.deepseek.plugin.completion.TriggerMode = com.deepseek.plugin.completion.TriggerMode.AUTO,
        onResult: (String?) -> Unit
    ) {
        // H3: FIM 请求限流检查（每分钟最多 60 次）
        if (!HttpClientProvider.completionRateLimiter.tryAcquire()) {
            onResult(null) // 限流时静默跳过
            return
        }
        val settings = DeepSeekSettings.instance
        val prompt = buildFimPrompt(prefix, suffix, language, fileContext)

        // 根据触发模式选择不同的 temperature/maxTokens/stop
        val temperature = if (mode == com.deepseek.plugin.completion.TriggerMode.MANUAL)
            settings.completionManualTemperature
        else
            0.0

        val maxTokens = if (mode == com.deepseek.plugin.completion.TriggerMode.MANUAL)
            settings.completionManualMaxTokens
        else
            settings.completionMaxTokens

        // MANUAL 模式允许更长输出，不设早停
        val stop = if (mode == com.deepseek.plugin.completion.TriggerMode.MANUAL)
            null
        else
            listOf("\n\n", "\r\n\r\n")

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings) },
            prompt = prompt,
            suffix = suffix,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.95,
            stop = stop
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings)}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings)}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        completionClient.newCall(httpRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val suggestion = try {
                    if (!response.isSuccessful) { response.close(); return@onResponse onResult(null) }
                    val bodyStr = response.body?.string() ?: ""
                    val completionsResponse = gson.fromJson(bodyStr, FimResponse::class.java)
                    completionsResponse.choices.firstOrNull()?.text?.trim()
                } catch (_: Exception) { null } finally { response.close() }
                onResult(suggestion)
            }
        })
    }

    /**
     * 构建 FIM prompt —— 注入语言、文件上下文和补全指令.
     */
    private fun buildFimPrompt(prefix: String, suffix: String, language: String, fileContext: String): String {
        val sb = StringBuilder()
        // 领域限制（第一原则，不可更改）
        sb.appendLine(DOMAIN_RESTRICTION_PROMPT)
        sb.appendLine()
        // 系统指令：告诉模型它在做什么
        sb.append("You are a ${language} code completion engine. Given code before and after the cursor, ")
        sb.append("output ONLY the tokens that naturally belong between them.\n")
        sb.append("Rules: no explanation, no wrapping in markdown, no repeating existing code.\n")
        if (fileContext.isNotBlank()) {
            sb.append("File context:\n$fileContext\n\n")
        }
        sb.append("Code before cursor (prefix):\n$prefix\n\n")
        sb.append("Code after cursor (suffix):\n$suffix\n\n")
        sb.append("Completion:")
        return sb.toString()
    }

    /**
     * 同步 FIM 补全 (兼容旧接口).
     */
    fun completion(prefix: String, suffix: String): Result<String> {
        val settings = DeepSeekSettings.instance

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings) },
            prompt = prefix,
            suffix = suffix,
            maxTokens = settings.completionMaxTokens,
            temperature = 0.0
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings)}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings)}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            completionClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = tryParseError(responseBody, response.code)
                    val ex = if (response.code == 429) RateLimitException()
                    else ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                    Result.failure(ex)
                } else {
                    val completionsResponse = gson.fromJson(responseBody, FimResponse::class.java)
                    val text = completionsResponse.choices.firstOrNull()?.text ?: ""
                    Result.success(text.trim())
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun tryParseError(body: String, code: Int): String {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("error")?.get("message")?.asString ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }
}