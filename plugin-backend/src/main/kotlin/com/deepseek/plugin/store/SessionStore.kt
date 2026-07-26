package com.deepseek.plugin.store

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.ChatSession
import com.deepseek.plugin.api.SessionException
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists chat sessions to disk, loads them on startup.
 * Sessions are kept indefinitely.
 *
 * 防 crash 设计：
 * - Atomic write：先写 .tmp 再 rename，避免写入中途崩溃导致文件损坏
 * - 备份文件：每次成功保存时同步更新 .bak 文件
 * - 自动恢复：主文件损坏时自动从 .bak 恢复
 *
 * File: <project>/.idea/deepseek-sessions.json
 * Backup: <project>/.idea/deepseek-sessions.json.bak
 */
class SessionStore(private val projectBasePath: String?) {

    companion object {
        private val LOG = Logger.getInstance(SessionStore::class.java)
        private val gson = Gson()
        private const val FILE_NAME = "deepseek-sessions.json"
        private const val BACKUP_NAME = "deepseek-sessions.json.bak"
    }

    data class SessionData(
        val name: String,
        val messages: MutableList<ChatMessage>,
        var totalTokens: Int,
        var lastActiveTime: Long
    )

    data class StoreData(
        val sessions: MutableList<SessionData> = mutableListOf(),
        var sessionCounter: Int = 1
    )

    /** 主文件 */
    private val file: File?
        get() {
            val base = projectBasePath ?: return null
            val dir = File(base, ".idea")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, FILE_NAME)
        }

    /** 备份文件 */
    private val backupFile: File?
        get() {
            val base = projectBasePath ?: return null
            val dir = File(base, ".idea")
            return File(dir, BACKUP_NAME)
        }

    /**
     * Load sessions from disk. Returns null if nothing saved.
     * 自动从备份恢复损坏的主文件。
     */
    fun load(): Pair<List<ChatSession>, Int>? {
        val f = file ?: return null
        if (!f.exists()) return null

        // 尝试加载主文件
        val primary = tryLoad(f)
        if (primary != null) return primary

        // 主文件损坏，尝试从备份恢复
        val bak = backupFile
        if (bak != null && bak.exists()) {
            LOG.warn("主会话文件损坏，正在从备份恢复: ${bak.absolutePath}")
            val restored = tryLoad(bak)
            if (restored != null) {
                // 恢复成功 → 用备份覆盖主文件
                try {
                    Files.copy(bak.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    LOG.warn("无法从备份恢复会话文件", e)
                }
                return restored
            }
        }

        // 备份也损坏了
        LOG.error("会话文件及备份均损坏，会话丢失")
        return null
    }

    /** 尝试从指定文件加载，返回 null 表示文件损坏 */
    private fun tryLoad(f: File): Pair<List<ChatSession>, Int>? {
        return try {
            val json = f.readText(Charsets.UTF_8)
            val store = gson.fromJson(json, StoreData::class.java) ?: return null

            val sessions = store.sessions.map { d ->
                ChatSession(d.name, d.messages, d.totalTokens, d.lastActiveTime)
            }

            Pair(sessions, store.sessionCounter)
        } catch (e: Exception) {
            LOG.warn("无法加载会话文件: ${f.absolutePath}", e)
            null
        }
    }

    /**
     * Save sessions to disk.
     *
     * 写入策略：
     * 1. 先原子写入 .tmp 文件
     * 2. rename .tmp → 主文件
     * 3. 同步复制到 .bak 备份
     */
    fun save(sessions: List<ChatSession>, sessionCounter: Int) {
        val f = file ?: return
        try {
            val store = StoreData(
                sessions = sessions.map {
                    SessionData(it.name, it.messages, it.totalTokens, it.lastActiveTime)
                }.toMutableList(),
                sessionCounter = sessionCounter
            )
            val json = gson.toJson(store)

            // 1. 原子写入 .tmp
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)

            // 2. rename → 主文件
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            // 3. 同步备份
            val bak = backupFile
            if (bak != null) {
                try {
                    Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: Exception) {
                    LOG.warn("无法创建会话备份文件", e)
                }
            }
        } catch (e: Exception) {
            SessionException("保存会话失败", e).also {
                LOG.error(it.toLogString())
            }
        }
    }
}
