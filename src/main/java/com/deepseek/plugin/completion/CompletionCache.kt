package com.deepseek.plugin.completion

import com.intellij.openapi.diagnostic.Logger
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 补全结果 LRU 缓存。
 *
 * 借鉴 deepseek-copilot TriggerCenter 的缓存策略。
 * 按 (filePath, line, column±5, prefixNormalized) 作为缓存键，
 * 当用户在同一位置附近再次触发补全时，直接返回缓存结果，跳过 API 调用。
 *
 * 线程安全：使用 ReentrantReadWriteLock 保护并发访问。
 */
class CompletionCache(private val maxSize: Int = 20) {

    companion object {
        private val LOG = Logger.getInstance(CompletionCache::class.java)

        /** 位置匹配窗口大小（字符数） */
        private const val POSITION_WINDOW = 5

        /** 全局单例 */
        @Volatile
        private var _instance: CompletionCache? = null

        fun getInstance(): CompletionCache {
            if (_instance == null) {
                synchronized(this) {
                    if (_instance == null) {
                        _instance = CompletionCache()
                    }
                }
            }
            return _instance!!
        }

        /** 由应用设置更新缓存大小 */
        fun resetInstance(maxSize: Int) {
            synchronized(this) {
                _instance = CompletionCache(maxSize)
            }
        }
    }

    /** 缓存键 */
    data class CacheKey(
        val filePath: String,
        val line: Int,
        val column: Int,
        /** 归一化的前缀文本（前 50 个字符） */
        val prefixNormalized: String
    )

    /** 缓存条目 */
    data class CacheEntry(
        val key: CacheKey,
        val suggestion: String,
        /** 缓存命中次数 */
        var hitCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** 底层 LRU 缓存 (access-order = true) */
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > maxSize
        }
    }

    private val lock = ReentrantReadWriteLock()

    // ==================== 对外 API ====================

    /**
     * 根据上下文查找缓存。
     *
     * @param filePath 文件路径
     * @param line 光标行号（0-based）
     * @param column 光标列号（0-based）
     * @param prefix 光标前的文本
     * @return 缓存的补全文本，未命中则返回 null
     */
    fun get(filePath: String, line: Int, column: Int, prefix: String): String? {
        if (maxSize <= 0) return null

        val normalizedPrefix = normalizePrefix(prefix)

        return lock.read<String?> {
            // 精确匹配
            val exactKey = CacheKey(filePath, line, column, normalizedPrefix)
            val exactEntry = cache[exactKey]
            if (exactEntry != null) {
                exactEntry.hitCount++
                LOG.debug("Cache HIT (exact): file=$filePath line=$line col=$column hits=${exactEntry.hitCount}")
                return@read exactEntry.suggestion
            }

            // 近似匹配：同一行，列 ±POSITION_WINDOW
            val candidates = cache.values.filter { entry ->
                entry.key.filePath == filePath &&
                    entry.key.line == line &&
                    kotlin.math.abs(entry.key.column - column) <= POSITION_WINDOW
            }

            // 在有近似匹配的情况下，尝试模糊前缀匹配
            for (entry in candidates) {
                if (normalizedPrefix.startsWith(entry.key.prefixNormalized) ||
                    entry.key.prefixNormalized.startsWith(normalizedPrefix)
                ) {
                    entry.hitCount++
                    LOG.debug("Cache HIT (fuzzy): file=$filePath line=$line col=$column hits=${entry.hitCount}")
                    return@read entry.suggestion
                }
            }

            LOG.debug("Cache MISS: file=$filePath line=$line col=$column")
            null
        }
    }

    /**
     * 记录补全结果到缓存。
     */
    fun put(filePath: String, line: Int, column: Int, prefix: String, suggestion: String) {
        if (maxSize <= 0) return
        if (suggestion.isBlank()) return

        val normalizedPrefix = normalizePrefix(prefix)
        val key = CacheKey(filePath, line, column, normalizedPrefix)
        val entry = CacheEntry(key = key, suggestion = suggestion)

        lock.write {
            cache[key] = entry
            LOG.debug("Cache PUT: file=$filePath line=$line col=$column suggestion_len=${suggestion.length}")
        }
    }

    /** 清空缓存 */
    fun clear() {
        lock.write {
            cache.clear()
        }
    }

    /** 当前缓存大小 */
    fun size(): Int = lock.read { cache.size }

    /** 缓存命中总次数 */
    fun totalHits(): Int = lock.read { cache.values.sumOf { it.hitCount } }

    // ==================== 辅助方法 ====================

    /** 归一化前缀文本：取前 50 个字符并去除多余的空白 */
    private fun normalizePrefix(prefix: String): String {
        return prefix.trimEnd().take(50).replace(Regex("\\s+"), " ")
    }
}
