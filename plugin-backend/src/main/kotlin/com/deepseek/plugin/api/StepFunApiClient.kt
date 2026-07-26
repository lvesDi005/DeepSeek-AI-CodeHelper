package com.deepseek.plugin.api

import com.deepseek.plugin.settings.DeepSeekSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * 图片解析客户端，支持多供应商.
 *
 * 根据 settings.imageParsingModel 选择图片解析供应商：
 * - "agnes"   → 使用 Agnes API (复用 Agnes 密钥)
 * - "stepfun"  → 使用 StepFun API (使用独立的 StepFun 密钥)
 * - "nvidia"  → 使用 NVIDIA API (复用 NVIDIA 密钥)
 *
 * 将图片解析为文本描述，然后注入到 Chat 上下文中。
 */
class StepFunApiClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    /** 使用共享连接池的 HTTP 客户端 */
    private val client get() = HttpClientProvider.stepFunClient

    /** 追踪当前正在执行的 HTTP Call，用于强制取消 */
    private val currentCall = AtomicReference<Call?>(null)

    /**
     * 强制取消当前正在执行的图片解析请求（如果有）。
     * OkHttp 的 [Call.cancel] 会使 [Call.execute] 立即抛出 IOException("Canceled")。
     */
    fun cancelCurrentCall() {
        currentCall.getAndSet(null)?.cancel()
    }

    // ── 供应商配置 ──

    private data class ProviderConfig(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val displayName: String
    )

    private fun resolveProvider(settings: DeepSeekSettings): ProviderConfig {
        return when (settings.imageParsingModel) {
            "stepfun" -> ProviderConfig(
                baseUrl = "https://api.stepfun.com/v1",
                apiKey = settings.stepFunApiKey,
                model = "step-1o-turbo-vision",
                displayName = "StepFun"
            )
            "nvidia" -> ProviderConfig(
                baseUrl = settings.nvidiaBaseUrl.trimEnd('/'),
                apiKey = settings.nvidiaApiKey,
                model = "meta/llama-4-maverick-17b-128e-instruct",
                displayName = "NVIDIA Llama Vision"
            )
            else -> ProviderConfig(  // "agnes" (default)
                baseUrl = settings.agnesBaseUrl.trimEnd('/'),
                apiKey = settings.agnesApiKey,
                model = settings.agnesModel.ifBlank { "agnes-2.0-flash" },
                displayName = "Agnes Image 2.1 Flash"
            )
        }
    }

    // ── 请求/响应模型 ──

    data class ContentPart(
        val type: String,  // "text" | "image_url"
        val text: String? = null,
        @SerializedName("image_url") val imageUrl: ImageUrl? = null
    )

    data class ImageUrl(
        val url: String  // base64 data URI or HTTP URL
    )

    data class VisionMessage(
        val role: String,
        val content: List<ContentPart>
    )

    data class VisionRequest(
        val model: String,
        val messages: List<VisionMessage>,
        @SerializedName("max_tokens") val maxTokens: Int = 4096,
        val stream: Boolean = false
    )

    data class VisionChoice(
        val index: Int,
        val message: VisionResponseMessage?,
        @SerializedName("finish_reason") val finishReason: String? = null
    )

    data class VisionResponseMessage(
        val role: String? = null,
        val content: String? = null
    )

    data class VisionResponse(
        val id: String? = null,
        val `object`: String? = null,
        val created: Long? = null,
        val model: String? = null,
        val choices: List<VisionChoice>? = null,
        val usage: Usage? = null
    )

    // ── 公开方法 ──

    /**
     * 解析单张图片文件, 返回文本描述.
     *
     * @param imagePath 图片文件的绝对路径
     * @param prompt    可选的提示词, 默认要求模型详细描述图片
     * @return 图片的文本描述, 失败时返回错误信息
     */
    fun parseImage(imagePath: String, prompt: String = "请详细描述这张图片中的内容"): Result<String> {
        val settings = DeepSeekSettings.instance
        val provider = resolveProvider(settings)

        if (provider.apiKey.isBlank()) {
            val providerField = when (settings.imageParsingModel) {
                "stepfun" -> "StepFun API Key"
                "nvidia" -> "NVIDIA API Key"
                else -> "API Key (Agnes section)"
            }
            return Result.failure(IOException(
                "${provider.displayName} API Key 未配置，请在 Settings → Tools → DeepSeek AI 的 $providerField 中设置"
            ))
        }

        // H3: 图片解析限流检查（每分钟最多 10 次）
        if (!HttpClientProvider.stepFunRateLimiter.tryAcquire()) {
            return Result.failure(RateLimitException("图片解析过于频繁，请稍后再试"))
        }

        // 读取图片并转为 base64 data URI
        val dataUri = readImageAsBase64(imagePath)
            ?: return Result.failure(IOException("无法读取图片文件: $imagePath"))

        val request = VisionRequest(
            model = provider.model,
            messages = listOf(
                VisionMessage(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(type = "image_url", imageUrl = ImageUrl(url = dataUri))
                    )
                )
            ),
            maxTokens = 4096
        )

        val body = gson.toJson(request).toRequestBody(JSON_MEDIA)
        val httpRequest = Request.Builder()
            .url("${provider.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val call = client.newCall(httpRequest)
        currentCall.set(call)
        return try {
            val result = call.execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = tryParseError(responseBody, response.code)
                    Result.failure(IOException("${provider.displayName} API error ${response.code}: $errMsg"))
                } else {
                    val visionResp = gson.fromJson(responseBody, VisionResponse::class.java)
                    val content = visionResp.choices?.firstOrNull()?.message?.content ?: ""
                    if (content.isBlank()) {
                        Result.failure(IOException("${provider.displayName} 返回了空内容"))
                    } else {
                        Result.success(content.trim())
                    }
                }
            }
            result
        } catch (e: IOException) {
            Result.failure(e)
        } finally {
            currentCall.set(null)
        }
    }

    /**
     * 批量解析多张图片, 返回每张图片的描述列表.
     * 每张图片独立调用 API.
     */
    fun parseImages(imagePaths: List<String>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        for (path in imagePaths) {
            val fileName = Paths.get(path).fileName.toString()
            val result = parseImage(path)
            val description = result.getOrElse { e -> "[图片解析失败: ${e.message}]" }
            results.add(fileName to description)
        }
        return results
    }

    // ── 内部方法 ──

    /**
     * 读取图片文件并转换为 base64 data URI 字符串.
     * 支持 PNG, JPEG, GIF, BMP, WEBP 等常见格式.
     */
    private fun readImageAsBase64(imagePath: String): String? {
        return try {
            val bytes = Files.readAllBytes(Paths.get(imagePath))
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val mimeType = detectMimeType(imagePath)
            "data:$mimeType;base64,$base64"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据文件扩展名检测 MIME 类型.
     */
    private fun detectMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/png" // 默认
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
