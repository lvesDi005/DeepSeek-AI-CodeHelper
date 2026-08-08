package com.deepseek.plugin.cli

import com.deepseek.plugin.store.ChangeManagementStore
import com.deepseek.plugin.store.ChangeRecord
import com.deepseek.plugin.store.FileChangeInfo
import com.intellij.openapi.project.Project
import java.io.File

/**
 * CLI Agent 变更追踪：执行前快照 → 执行后对比 → 写入 [ChangeManagementStore]（可回滚）。
 *
 * 只追踪文本源码文件（按扩展名白名单），排除构建产物与 VCS/IDE 目录。
 */
object CliChangeTracker {

    private val EXCLUDED_DIRS = setOf(
        ".git", ".idea", "build", "node_modules", ".gradle", "target", "dist", "out",
        ".deepseek", ".intellijPlatform", ".kotlin"
    )

    private val EXTENSIONS = setOf(
        "java", "kt", "kts", "py", "js", "ts", "jsx", "tsx", "go", "rs", "c", "h", "cpp", "hpp",
        "cs", "php", "rb", "swift", "vue", "json", "xml", "yml", "yaml", "toml", "gradle",
        "properties", "md", "txt", "html", "css", "scss", "sql", "sh", "bat", "ini", "conf", "env"
    )

    /**
     * 对项目根目录下的文本源码文件做内容快照。
     * @return 相对路径(正斜杠) → 文件内容
     */
    fun snapshot(projectDir: File): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        if (!projectDir.isDirectory) return map
        try {
            projectDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                .filter { file ->
                    try {
                        val rel = file.relativeTo(projectDir).toString().replace('\\', '/')
                        rel.split('/').none { it in EXCLUDED_DIRS }
                    } catch (_: Exception) {
                        false
                    }
                }
                .forEach { f ->
                    try {
                        map[f.relativeTo(projectDir).path.replace('\\', '/')] = f.readBytes()
                    } catch (_: Exception) { }
                }
        } catch (_: Exception) { }
        return map
    }

    /**
     * 对比前后快照，将变更写入 [ChangeManagementStore]。
     * @return 记录的文件变更数（0 = 无变化）
     */
    fun recordChanges(
        project: Project,
        before: Map<String, ByteArray>,
        after: Map<String, ByteArray>,
        title: String
    ): Int {
        val changes = mutableListOf<FileChangeInfo>()
        val allKeys = before.keys + after.keys
        for (key in allKeys.distinct()) {
            val b = before[key]
            val a = after[key]
            when {
                b == null && a != null ->
                    changes.add(FileChangeInfo(key, ByteArray(0), isNew = true))
                b != null && a != null && !b.contentEquals(a) ->
                    changes.add(FileChangeInfo(key, b))
                // 被 CLI 删除的文件不回滚（ChangeManagementStore 不支持删除恢复）
            }
        }
        if (changes.isEmpty()) return 0
        val store = project.getService(ChangeManagementStore::class.java)
        store.addRecord(ChangeRecord(title = title, changes = changes))
        return changes.size
    }
}
