package com.deepseek.plugin.api

import com.deepseek.plugin.api.SettingsSnapshot
import com.deepseek.plugin.api.TriggerMode
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.settings.readClaudeModelMapping
import com.deepseek.plugin.settings.toSnapshot
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

        /** Anthropic Messages API 版本头 */
        private const val ANTHROPIC_API_VERSION = "2023-06-01"

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
        val prov = provider(settings)
        val temp = prov.temperature ?: settings.temperature
        return chatSyncWithExplicitConfig(
            baseUrl = prov.baseUrl(settings.toSnapshot()),
            apiKey = prov.apiKey(settings.toSnapshot()),
            model = prov.model(settings.toSnapshot()),
            temperature = temp,
            maxTokens = settings.maxTokens,
            messages = messages,
            protocol = prov.protocol,
            reasoningEffort = settings.codexReasoningEffort.takeIf { prov.id == "codex" }
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
        messages: List<ChatMessage>,
        protocol: String = "openai",
        reasoningEffort: String? = null
    ): Result<String> {
        // H3: 请求限流检查
        if (!HttpClientProvider.chatRateLimiter.tryAcquire()) {
            return Result.failure(RateLimitException())
        }
        // Anthropic 原生 Messages API 协议（cc-switch 等第三方 Anthropic 兼容中转）
        if (protocol == "anthropic") {
            return anthropicChatSync(baseUrl, apiKey, model, temperature, maxTokens, messages)
        }
        // OpenAI Responses API 协议（Codex CLI 直连模式，cc-switch 供应商原生 Responses）
        if (protocol == "codex-responses") {
            return codexResponsesChatSync(baseUrl, apiKey, model, temperature, maxTokens, messages, reasoningEffort)
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
                        if (content.isBlank()) Result.failure(ApiException("Chat API returned an empty response"))
                        else Result.success(content)
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
        onError: (Throwable) -> Unit,
        onReasoningToken: ((String) -> Unit)? = null
    ): EventSource {
        val settings = DeepSeekSettings.instance
        val prov = provider(settings)
        val temp = prov.temperature ?: settings.temperature
        return chatStreamWithExplicitConfig(
            baseUrl = prov.baseUrl(settings.toSnapshot()),
            apiKey = prov.apiKey(settings.toSnapshot()),
            model = prov.model(settings.toSnapshot()),
            temperature = temp,
            maxTokens = settings.maxTokens,
            messages = messages,
            protocol = prov.protocol,
            reasoningEffort = settings.codexReasoningEffort.takeIf { prov.id == "codex" },
            onToken = onToken,
            onComplete = onComplete,
            onError = onError,
            onReasoningToken = onReasoningToken
        )
    }

    /**
     * 流式调用 —— 使用显式指定的 API 配置（用于多 Agent 分工，每个 Agent 使用不同模型/Provider）。
     *
     * @param baseUrl  API base URL（如 "https://api.deepseek.com/v1"）
     * @param apiKey   API Key
     * @param model    模型名（如 "deepseek-v4-pro"、"deepseek-v4-flash"、"agnes-2.5-flash"）
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
        protocol: String = "openai",
        reasoningEffort: String? = null,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit,
        onReasoningToken: ((String) -> Unit)? = null
    ): EventSource {
        // 请求限流检查
        if (!HttpClientProvider.chatRateLimiter.tryAcquire()) {
            onError(RateLimitException())
            return object : EventSource {
                override fun cancel() {}
                override fun request() = Request.Builder().url("$baseUrl/chat/completions").build()
            }
        }
        // Anthropic 原生 Messages API 协议（cc-switch 等第三方 Anthropic 兼容中转）
        if (protocol == "anthropic") {
            return anthropicChatStream(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                messages = messages,
                onToken = onToken,
                onComplete = onComplete,
                onError = onError,
                onReasoningToken = onReasoningToken
            )
        }
        // OpenAI Responses API 协议（Codex CLI 直连模式，cc-switch 供应商原生 Responses）
        if (protocol == "codex-responses") {
            return codexResponsesChatStream(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                temperature = temperature,
                maxTokens = maxTokens,
                messages = messages,
                reasoningEffort = reasoningEffort,
                onToken = onToken,
                onComplete = onComplete,
                onError = onError
            )
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
                    val reasoningContent = delta?.reasoningContent ?: ""
                    if (reasoningContent.isNotEmpty()) {
                        onReasoningToken?.invoke(reasoningContent)
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
        mode: TriggerMode = TriggerMode.AUTO,
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
        val temperature = if (mode == TriggerMode.MANUAL)
            settings.completionManualTemperature
        else
            0.0

        val maxTokens = if (mode == TriggerMode.MANUAL)
            settings.completionManualMaxTokens
        else
            settings.completionMaxTokens

        // MANUAL 模式允许更长输出，不设早停
        val stop = if (mode == TriggerMode.MANUAL)
            null
        else
            listOf("\n\n", "\r\n\r\n")

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings.toSnapshot()) },
            prompt = prompt,
            suffix = suffix,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.95,
            stop = stop
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings.toSnapshot())}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings.toSnapshot())}")
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
     * 流式 FIM 补全 —— 使用 SSE 逐 token 返回结果，首 token 即可展示。
     *
     * @param onToken    每收到一个新 token 时回调（可来自后台线程）
     * @param onComplete 所有 token 接收完毕时回调（含完整文本 + usage）
     * @param onError    出错时回调
     * @return EventSource 句柄，可用于取消
     */
    fun completionFimStream(
        prefix: String,
        suffix: String,
        language: String,
        fileContext: String,
        mode: TriggerMode = TriggerMode.AUTO,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String) -> Unit,
        onError: (Throwable) -> Unit
    ): okhttp3.sse.EventSource {
        // 限流检查
        if (!HttpClientProvider.completionRateLimiter.tryAcquire()) {
            onError(okhttp3.internal.http2.ConnectionShutdownException())
            return object : okhttp3.sse.EventSource {
                override fun cancel() {}
                override fun request() = Request.Builder().url("https://unknown").build()
            }
        }
        val settings = DeepSeekSettings.instance
        val prompt = buildFimPrompt(prefix, suffix, language, fileContext)

        val temperature = if (mode == TriggerMode.MANUAL)
            settings.completionManualTemperature else 0.0
        val maxTokens = if (mode == TriggerMode.MANUAL)
            settings.completionManualMaxTokens else settings.completionMaxTokens
        val stop = if (mode == TriggerMode.MANUAL)
            null else listOf("\n\n", "\r\n\r\n")

        val request = FimRequest(
            model = settings.completionModel.ifBlank { provider(settings).model(settings.toSnapshot()) },
            prompt = prompt,
            suffix = suffix,
            maxTokens = maxTokens,
            temperature = temperature,
            topP = 0.95,
            stop = stop,
            stream = true
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)
        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings.toSnapshot())}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings.toSnapshot())}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val factory = okhttp3.sse.EventSources.createFactory(completionClient)
        val fullResponse = StringBuilder()
        var completed = false

        return factory.newEventSource(httpRequest, object : okhttp3.sse.EventSourceListener() {
            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    if (!completed) {
                        completed = true
                        onComplete(fullResponse.toString())
                    }
                    return
                }
                try {
                    val chunk = gson.fromJson(data, FimStreamChunk::class.java)
                    val text = chunk?.choices?.firstOrNull()?.text ?: ""
                    if (text.isNotEmpty()) {
                        fullResponse.append(text)
                        onToken(text)
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable?, response: okhttp3.Response?) {
                if (completed) return
                completed = true
                onError(t ?: Exception("FIM stream failed: HTTP ${response?.code}"))
            }

            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                if (!completed) {
                    completed = true
                    onComplete(fullResponse.toString())
                }
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
            model = settings.completionModel.ifBlank { provider(settings).model(settings.toSnapshot()) },
            prompt = prefix,
            suffix = suffix,
            maxTokens = settings.completionMaxTokens,
            temperature = 0.0
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("${provider(settings).baseUrl(settings.toSnapshot())}/completions")
            .header("Authorization", "Bearer ${provider(settings).apiKey(settings.toSnapshot())}")
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

    // ============ Anthropic 原生 Messages API（cc-switch 等 Anthropic 兼容中转） ============

    /** Anthropic Messages API 端点 URL：{base}/v1/messages（baseUrl 可能已含 /v1 后缀） */
    private fun anthropicMessagesUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/').removeSuffix("/v1")
        return "$base/v1/messages"
    }

    /** 构造 Anthropic Messages API 请求体（system 拆为顶层字段，role 仅 user/assistant） */
    internal fun buildAnthropicBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val msgs = JsonArray().apply {
            messages.filter { it.role != "system" }.forEach { message ->
                add(JsonObject().apply {
                    addProperty("role", message.role)
                    if (message.parts.isNullOrEmpty()) {
                        addProperty("content", message.content)
                    } else {
                        add("content", buildAnthropicContent(message))
                    }
                })
            }
        }
        val obj = JsonObject().apply {
            addProperty("model", mapAnthropicModel(model))
            addProperty("max_tokens", maxTokens)
            if (system.isNotBlank()) addProperty("system", system)
            // Anthropic 不接受 temperature=0，且范围 0~1
            if (temperature in 0.01..1.0) addProperty("temperature", temperature)
            addProperty("stream", stream)
            add("messages", msgs)
        }
        return obj.toString()
    }

    private fun buildAnthropicContent(message: ChatMessage): JsonArray = JsonArray().apply {
        if (message.content.isNotBlank()) {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", message.content)
            })
        }
        message.parts.orEmpty().forEach { part ->
            when (part.type) {
                ChatContentType.TEXT -> part.text?.takeIf { it.isNotBlank() }?.let { text ->
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", text)
                    })
                }
                ChatContentType.IMAGE -> parseDataUri(part.dataUri)?.let { (mediaType, data) ->
                    add(JsonObject().apply {
                        addProperty("type", "image")
                        add("source", JsonObject().apply {
                            addProperty("type", "base64")
                            addProperty("media_type", part.mediaType ?: mediaType)
                            addProperty("data", data)
                        })
                    })
                }
            }
        }
    }

    private fun parseDataUri(dataUri: String?): Pair<String, String>? {
        if (dataUri.isNullOrBlank() || !dataUri.startsWith("data:")) return null
        val comma = dataUri.indexOf(',')
        if (comma <= 5) return null
        val metadata = dataUri.substring(5, comma)
        if (!metadata.endsWith(";base64")) return null
        val mediaType = metadata.removeSuffix(";base64")
        val data = dataUri.substring(comma + 1)
        if (mediaType.isBlank() || data.isBlank()) return null
        return mediaType to data
    }

    /** 按 cc-switch / Claude Code 约定映射模型名（ANTHROPIC_DEFAULT_*_MODEL → 实际模型） */
    private fun mapAnthropicModel(model: String): String {
        val lower = model.lowercase()
        val key = when {
            lower.contains("sonnet") -> "sonnet"
            lower.contains("haiku") -> "haiku"
            lower.contains("opus") -> "opus"
            else -> null
        }
        return key?.let { readClaudeModelMapping()[it] } ?: model
    }

    /** Anthropic 原生 Messages API 同步调用 */
    private fun anthropicChatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>
    ): Result<String> {
        val body = buildAnthropicBody(model, messages, temperature, maxTokens, stream = false)
        val httpRequest = Request.Builder()
            .url(anthropicMessagesUrl(baseUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return retryWithBackoff {
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
                        val obj = gson.fromJson(responseBody, JsonObject::class.java)
                        val content = obj.getAsJsonArray("content")
                            ?.mapNotNull { it.asJsonObject.get("text")?.asString }
                            ?.joinToString("") ?: ""
                        Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    /** Anthropic 原生 Messages API 流式调用（SSE：content_block_delta → delta.text / delta.thinking） */
    private fun anthropicChatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit,
        onReasoningToken: ((String) -> Unit)?
    ): EventSource {
        val body = buildAnthropicBody(model, messages, temperature, maxTokens, stream = true)
        val httpRequest = Request.Builder()
            .url(anthropicMessagesUrl(baseUrl))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_API_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val factory = EventSources.createFactory(streamClient)
        val fullResponse = StringBuilder()
        var lastUsage: Usage? = null
        var completed = false

        return factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = gson.fromJson(data, JsonObject::class.java) ?: return
                    when (obj.get("type")?.asString) {
                        "message_start" -> {
                            val u = obj.getAsJsonObject("message")?.getAsJsonObject("usage")
                            val input = u?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                            if (input > 0) lastUsage = Usage(input, 0, input)
                        }
                        "content_block_delta" -> {
                            val delta = obj.getAsJsonObject("delta")
                            when (delta.get("type")?.asString) {
                                "text_delta" -> {
                                    val text = delta.get("text")?.asString ?: ""
                                    if (text.isNotEmpty()) {
                                        fullResponse.append(text)
                                        onToken(text)
                                    }
                                }
                                "thinking_delta" -> {
                                    val thinking = delta.get("thinking")?.asString ?: ""
                                    if (thinking.isNotEmpty()) onReasoningToken?.invoke(thinking)
                                }
                            }
                        }
                        "message_delta" -> {
                            val u = obj.getAsJsonObject("usage")
                            val input = lastUsage?.promptTokens
                                ?: u?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt
                                ?: 0
                            val output = u?.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                            lastUsage = Usage(input, output, input + output)
                        }
                        "message_stop" -> {
                            if (!completed) {
                                completed = true
                                if (fullResponse.isBlank()) onError(ApiException("Anthropic returned an empty response"))
                                else onComplete(fullResponse.toString(), lastUsage)
                            }
                        }
                        "error" -> {
                            if (!completed) {
                                completed = true
                                val error = obj.getAsJsonObject("error")
                                onError(ApiException(error?.get("message")?.asString ?: "Anthropic stream error"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!completed) {
                        completed = true
                        eventSource.cancel()
                        onError(ApiException("Invalid Anthropic stream event: ${e.message}"))
                    }
                }
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
                    onError(ApiException("Anthropic stream closed before message_stop"))
                }
            }
        })
    }

    // ============ OpenAI Responses API（Codex CLI 直连模式） ============

    /**
     * 构造 /v1/responses 端点 URL。
     * baseUrl 可能含或不含 /v1 后缀，统一处理：
     *   "https://api.openai.com/v1"   → "https://api.openai.com/v1/responses"
     *   "https://api.openai.com"      → "https://api.openai.com/v1/responses"
     */
    private fun codexResponsesUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1")) "$base/responses" else "$base/v1/responses"
    }

    /** 构造 Responses API 请求体：system → instructions，其余 messages → input[] */
    internal fun buildCodexResponsesBody(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean,
        reasoningEffort: String? = null
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.content }
        val input = JsonArray().apply {
            messages.filter { it.role != "system" }.forEach { msg ->
                add(JsonObject().apply {
                    addProperty("role", msg.role)
                    if (msg.parts.isNullOrEmpty()) addProperty("content", msg.content)
                    else add("content", buildCodexContent(msg))
                })
            }
        }
        return JsonObject().apply {
            addProperty("model", model)
            if (system.isNotBlank()) addProperty("instructions", system)
            add("input", input)
            addProperty("max_output_tokens", maxTokens)
            reasoningEffort?.takeIf { it in setOf("low", "medium", "high") }?.let { effort ->
                add("reasoning", JsonObject().apply { addProperty("effort", effort) })
            }
            // Codex reasoning models and compatible proxies may reject temperature.
            addProperty("stream", stream)
        }.toString()
    }

    private fun buildCodexContent(message: ChatMessage): JsonArray = JsonArray().apply {
        if (message.content.isNotBlank()) {
            add(JsonObject().apply {
                addProperty("type", if (message.role == "assistant") "output_text" else "input_text")
                addProperty("text", message.content)
            })
        }
        message.parts.orEmpty().forEach { part ->
            when (part.type) {
                ChatContentType.TEXT -> part.text?.takeIf { it.isNotBlank() }?.let { text ->
                    add(JsonObject().apply {
                        addProperty("type", if (message.role == "assistant") "output_text" else "input_text")
                        addProperty("text", text)
                    })
                }
                ChatContentType.IMAGE -> part.dataUri?.takeIf { it.startsWith("data:") }?.let { imageUrl ->
                    add(JsonObject().apply {
                        addProperty("type", "input_image")
                        addProperty("image_url", imageUrl)
                    })
                }
            }
        }
    }

    /** 从非流式 Responses API 响应中提取 output_text 文本 */
    private fun parseCodexResponsesText(body: String): String {
        return try {
            gson.fromJson(body, JsonObject::class.java)
                .getAsJsonArray("output")
                ?.flatMap { item ->
                    item.asJsonObject.getAsJsonArray("content")
                        ?.mapNotNull { part ->
                            val p = part.asJsonObject
                            if (p.get("type")?.asString == "output_text") p.get("text")?.asString else null
                        } ?: emptyList()
                }?.joinToString("") ?: ""
        } catch (_: Exception) { "" }
    }

    /** Responses API 同步调用 */
    private fun codexResponsesChatSync(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>,
        reasoningEffort: String?
    ): Result<String> {
        val body = buildCodexResponsesBody(model, messages, temperature, maxTokens, stream = false, reasoningEffort)
        val httpRequest = Request.Builder()
            .url(codexResponsesUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        return retryWithBackoff {
            try {
                syncClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val errMsg = tryParseError(responseBody, response.code)
                        val ex = if (response.code == 429) RateLimitException()
                        else ApiException("API error ${response.code}: $errMsg", httpCode = response.code)
                        Result.failure(ex)
                    } else {
                        val content = parseCodexResponsesText(responseBody)
                        if (content.isBlank()) Result.failure(ApiException("Codex Responses API returned an empty response"))
                        else Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }

    /**
     * Responses API 流式调用（SSE 事件类型）：
     *  - response.output_text.delta → delta 字段，逐 token 回调
     *  - response.completed         → response.usage，触发 onComplete
     *  - error                      → 触发 onError
     */
    private fun codexResponsesChatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        temperature: Double,
        maxTokens: Int,
        messages: List<ChatMessage>,
        reasoningEffort: String?,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val body = buildCodexResponsesBody(model, messages, temperature, maxTokens, stream = true, reasoningEffort)
        val httpRequest = Request.Builder()
            .url(codexResponsesUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val factory = EventSources.createFactory(streamClient)
        val fullResponse = StringBuilder()
        var lastUsage: Usage? = null
        var completed = false

        return factory.newEventSource(httpRequest, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val obj = gson.fromJson(data, JsonObject::class.java) ?: return
                    when (obj.get("type")?.asString) {
                        "response.output_text.delta" -> {
                            val delta = obj.get("delta")?.asString ?: ""
                            if (delta.isNotEmpty()) {
                                fullResponse.append(delta)
                                onToken(delta)
                            }
                        }
                        "response.completed" -> {
                            val u = obj.getAsJsonObject("response")?.getAsJsonObject("usage")
                            if (u != null) {
                                val input = u.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                                val output = u.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                                lastUsage = Usage(input, output, input + output)
                            }
                            if (!completed) {
                                completed = true
                                if (fullResponse.isBlank()) onError(ApiException("Codex Responses API returned an empty response"))
                                else onComplete(fullResponse.toString(), lastUsage)
                            }
                        }
                        "error", "response.failed", "response.incomplete" -> {
                            if (!completed) {
                                completed = true
                                val responseError = obj.getAsJsonObject("response")?.getAsJsonObject("error")
                                val msg = obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                                    ?: responseError?.get("message")?.asString
                                    ?: "Codex Responses API stream failed"
                                onError(ApiException(msg))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!completed) {
                        completed = true
                        eventSource.cancel()
                        onError(ApiException("Invalid Codex Responses stream event: ${e.message}"))
                    }
                }
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
                    onError(ApiException("Codex Responses stream closed before response.completed"))
                }
            }
        })
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
