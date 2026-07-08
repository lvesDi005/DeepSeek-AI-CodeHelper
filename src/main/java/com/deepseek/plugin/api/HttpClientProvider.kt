package com.deepseek.plugin.api

import com.intellij.openapi.application.ApplicationManager
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全局共享的 OkHttpClient 单例。
 *
 * 所有 API 客户端共享同一个连接池和线程池，避免重复创建资源。
 *
 * 超时策略：
 * - [SHARED_CLIENT]：宽松超时（connect 30s / read 120s / call 180s），
 *   适用于流式 Chat 请求（需要长时间保持连接读取 token 流）。
 * - [chatSyncClient]：非流式 Chat 请求，callTimeout=90s。
 * - [completionClient]：FIM 补全请求，callTimeout=30s 快速响应。
 * - [stepFunClient]：图片解析，write 超时更长（上传大图），callTimeout=180s。
 * - [translateClient]：翻译请求，使用独立线程池和连接池，与 Chat 互不阻塞。
 */
object HttpClientProvider {

    /** 基础连接池配置——所有变体共享 */
    private val connectionPool = okhttp3.ConnectionPool(
        maxIdleConnections = 8,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    /** 默认 Dispatcher——最大并发请求数 */
    private val dispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 32
        maxRequestsPerHost = 8
    }

    init {
        // H5: 安装全局未捕获异常处理器，防止 OkHttp 回调线程 silent 死亡
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            System.err.println("[HttpClientProvider] Uncaught exception on thread ${thread.name}: ${throwable.message}")
            // 不重新抛出，避免 IDE 崩溃
        }
    }

    /** 最宽松的客户端：流式聊天用（可能长时间没有数据到达） */
    val SHARED_CLIENT: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .build()

    /** 非流式 Chat 客户端（Agent Actions 用），read 超时更短 */
    val chatSyncClient: OkHttpClient = SHARED_CLIENT.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    /** FIM 代码补全客户端，快速响应 */
    val completionClient: OkHttpClient = SHARED_CLIENT.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** StepFun 图片解析客户端，write 超时更长（上传大图） */
    val stepFunClient: OkHttpClient = SHARED_CLIENT.newBuilder()
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    // ══════════════════════════════════════════════════════════════
    //  翻译客户端 — 完全独立的连接池和调度器，与 Chat 互不干扰
    // ══════════════════════════════════════════════════════════════

    /** 翻译专用连接池 */
    private val translateConnectionPool = okhttp3.ConnectionPool(
        maxIdleConnections = 2,
        keepAliveDuration = 5,
        timeUnit = TimeUnit.MINUTES
    )

    /** 翻译专用 Dispatcher（独立线程池） */
    private val translateDispatcher = okhttp3.Dispatcher().apply {
        maxRequests = 4
        maxRequestsPerHost = 2
    }

    /**
     * 翻译客户端 — 使用完全独立的 Dispatcher 和 ConnectionPool，
     * 与 Chat / FIM / StepFun 不共享任何线程或连接资源，
     * 确保翻译请求不会阻塞对话或其他功能。
     */
    val translateClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .connectionPool(translateConnectionPool)
        .dispatcher(translateDispatcher)
        .build()

    // ══════════════════════════════════════════════════════════════
    // H3: 请求速率限制 (Sliding Window Log)
    // ══════════════════════════════════════════════════════════════

    /**
     * 滑动窗口速率限制器。
     *
     * 限制对同一 API 端点的请求频率，避免触发 API 限流（429）。
     * 使用 ConcurrentLinkedDeque 存储时间戳，线程安全、无锁。
     */
    class RateLimiter(
        /** 时间窗口长度（毫秒） */
        private val windowMs: Long = 60_000L,
        /** 窗口内允许的最大请求数 */
        private val maxRequests: Int = 30
    ) {
        private val timestamps = ConcurrentLinkedDeque<Long>()

        /**
         * 检查当前请求是否允许通过。
         * @return true=允许通过, false=被限流
         */
        fun tryAcquire(): Boolean {
            val now = System.currentTimeMillis()
            // 移除窗口之外的旧时间戳
            val cutoff = now - windowMs
            while (true) {
                val oldest = timestamps.peekFirst()
                if (oldest == null || oldest >= cutoff) break
                timestamps.pollFirst()
            }
            if (timestamps.size >= maxRequests) {
                return false
            }
            timestamps.addLast(now)
            return true
        }

        /** 重置计数器 */
        fun reset() {
            timestamps.clear()
        }
    }

    /** 通用 Chat API 限流器：每分钟 30 次 */
    val chatRateLimiter = RateLimiter(windowMs = 60_000L, maxRequests = 30)

    /** FIM 补全限流器：每分钟 60 次 */
    val completionRateLimiter = RateLimiter(windowMs = 60_000L, maxRequests = 60)

    /** StepFun 图片解析限流器：每分钟 10 次 */
    val stepFunRateLimiter = RateLimiter(windowMs = 60_000L, maxRequests = 10)
}
