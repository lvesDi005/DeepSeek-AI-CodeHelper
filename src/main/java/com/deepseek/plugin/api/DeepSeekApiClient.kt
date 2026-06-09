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
import java.util.concurrent.TimeUnit

class DeepSeekApiClient {

    companion object {
        private const val BASE_URL = "https://api.deepseek.com/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

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
        val request = ChatRequest(
            model = settings.model,
            messages = messages,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            stream = false
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errMsg = tryParseError(responseBody, response.code)
                Result.failure(IOException("API error ${response.code}: $errMsg"))
            } else {
                val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                val content = chatResponse.choices.firstOrNull()?.message?.content ?: ""
                Result.success(content)
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    // ============ 流式调用 (Chat Panel) ============

    fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (fullResponse: String, usage: Usage?) -> Unit,
        onError: (Throwable) -> Unit
    ): EventSource {
        val settings = DeepSeekSettings.instance
        val request = ChatRequest(
            model = settings.model,
            messages = messages,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            stream = true
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val factory = EventSources.createFactory(client)
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
                onError(IOException(errMsg, t))
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
     */
    fun completionFim(
        prefix: String,
        suffix: String,
        language: String,
        fileContext: String,
        onResult: (String?) -> Unit
    ) {
        val settings = DeepSeekSettings.instance
        val prompt = buildFimPrompt(prefix, suffix, language, fileContext)

        val request = FimRequest(
            model = settings.completionModel.ifBlank { settings.model },
            prompt = prompt,
            suffix = suffix,
            maxTokens = settings.completionMaxTokens,
            temperature = 0.0,
            topP = 0.95,
            stop = listOf("\n\n", "\r\n\r\n")
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(httpRequest).enqueue(object : Callback {
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
            model = settings.completionModel.ifBlank { settings.model },
            prompt = prefix,
            suffix = suffix,
            maxTokens = settings.completionMaxTokens,
            temperature = 0.0
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)

        val httpRequest = Request.Builder()
            .url("$BASE_URL/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Result.failure(IOException("API error ${response.code}: ${tryParseError(responseBody, response.code)}"))
            } else {
                val completionsResponse = gson.fromJson(responseBody, FimResponse::class.java)
                val text = completionsResponse.choices.firstOrNull()?.text ?: ""
                Result.success(text.trim())
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
