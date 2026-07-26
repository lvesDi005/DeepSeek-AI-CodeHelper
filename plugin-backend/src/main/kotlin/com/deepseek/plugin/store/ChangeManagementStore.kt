package com.deepseek.plugin.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.util.Base64

/**
 * 变更管理存储 — 按项目隔离，持久化到 `.idea/deepseek-changes.json`。
 *
 * 参考 [com.deepseek.plugin.settings.SkillStore] 的文件持久化模式：
 * - 每个项目独立的 `.idea/` 目录下的 JSON 文件
 * - 构造时加载，每次修改后立即保存
 * - 重新打开项目时变更记录自动恢复
 *
 * 备份的原始文件内容以 Base64 编码存储在 JSON 中。
 */
@Service(Service.Level.PROJECT)
class ChangeManagementStore(project: Project) {

    private val storeFile: File? = project.basePath?.let {
        val dir = File(it, ".idea")
        if (dir.isDirectory || dir.mkdirs()) File(dir, "deepseek-changes.json") else null
    }

    private val gson = Gson()

    /** 所有变更记录（按时间倒序，最新的在前） */
    private val _records = mutableListOf<ChangeRecord>()

    /** 对外只读视图 */
    val records: List<ChangeRecord> get() = _records.toList()

    init {
        load()
    }

    /**
     * 添加一条新的变更记录并持久化。
     */
    fun addRecord(record: ChangeRecord) {
        _records.add(0, record)
        save()
    }

    /**
     * 移除指定变更记录并持久化。
     */
    fun removeRecord(record: ChangeRecord) {
        _records.remove(record)
        save()
    }

    /**
     * 清空所有变更记录并持久化。
     */
    fun clearAll() {
        _records.clear()
        save()
    }

    /**
     * 根据 FileChangeInfo 回滚文件：
     * - 新建文件（isNew=true）：删除文件
     * - 修改文件（isNew=false）：将原始内容写回磁盘
     * @return true 表示回滚成功，false 表示失败
     */
    fun rollbackFile(change: FileChangeInfo): Boolean {
        return try {
            val file = File(change.filePath)
            if (change.isNew) {
                if (!file.exists()) {
                    System.err.println("[ChangeManagement] rollback: new file already deleted ${change.filePath}")
                    return true
                }
                val deleted = file.delete()
                if (deleted) {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(listOf(file))
                }
                deleted
            } else {
                if (!file.exists()) {
                    System.err.println("[ChangeManagement] rollback failed: file not found ${change.filePath}")
                    return false
                }
                file.writeBytes(change.originalContent)
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshIoFiles(listOf(file))
                true
            }
        } catch (e: Exception) {
            System.err.println("[ChangeManagement] rollback failed for ${change.filePath}: ${e.message}")
            false
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  文件持久化 — JSON (与 SkillStore 相同的模式)
    // ════════════════════════════════════════════════════════════════

    /** 从 JSON 文件加载变更记录 */
    private fun load() {
        val file = storeFile ?: return
        if (!file.exists()) return
        try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<ChangeRecordData>>() {}.type
            val dataList: List<ChangeRecordData> = gson.fromJson(json, type) ?: return
            _records.clear()
            for (data in dataList) {
                val changes = data.changes.map { fc ->
                    FileChangeInfo(
                        filePath = fc.filePath,
                        originalContent = Base64.getDecoder().decode(fc.originalContent),
                        isNew = fc.isNew,
                        timestamp = fc.timestamp
                    )
                }.toMutableList()
                _records.add(
                    ChangeRecord(
                        title = data.title,
                        timestamp = data.timestamp,
                        changes = changes
                    )
                )
            }
        } catch (e: Exception) {
            System.err.println("[ChangeManagement] Failed to load changes: ${e.message}")
        }
    }

    /** 将变更记录保存到 JSON 文件 */
    private fun save() {
        val file = storeFile ?: return
        try {
            val dataList = _records.map { record ->
                ChangeRecordData(
                    title = record.title,
                    timestamp = record.timestamp,
                    changes = record.changes.map { change ->
                        FileChangeData(
                            filePath = change.filePath,
                            originalContent = Base64.getEncoder().encodeToString(change.originalContent),
                            isNew = change.isNew,
                            timestamp = change.timestamp
                        )
                    }
                )
            }
            file.writeText(gson.toJson(dataList), Charsets.UTF_8)
        } catch (e: Exception) {
            System.err.println("[ChangeManagement] Failed to save changes: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  JSON 序列化 DTO（Base64 编码文件内容）
    // ════════════════════════════════════════════════════════════════

    private data class ChangeRecordData(
        val title: String,
        val timestamp: Long,
        val changes: List<FileChangeData>
    )

    private data class FileChangeData(
        val filePath: String,
        val originalContent: String, // Base64 编码
        val isNew: Boolean,
        val timestamp: Long
    )
}
