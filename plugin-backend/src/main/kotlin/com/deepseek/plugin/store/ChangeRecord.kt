package com.deepseek.plugin.store

import java.io.File

/**
 * 文件变更信息 — 记录单个文件的原始内容和路径。
 *
 * @param filePath      相对项目根目录的文件路径（用于显示和回滚定位）
 * @param originalContent 备份的原始文件内容（内存中），新建文件为 ByteArray(0)
 * @param isNew          是否为本次新建的文件（回滚时需删除而非写回）
 * @param timestamp      备份创建时间戳
 */
data class FileChangeInfo(
    val filePath: String,
    val originalContent: ByteArray,
    val isNew: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** 通过内容比对判断文件是否已被修改过（对于新建文件，检查文件是否还存在） */
    fun isModified(): Boolean {
        val f = File(filePath)
        if (!f.exists()) return false // 文件已被删除
        if (isNew) return false       // 新建文件，没有"修改"一说
        return f.readBytes().contentEquals(originalContent).not()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileChangeInfo) return false
        return filePath == other.filePath && originalContent.contentEquals(other.originalContent) && isNew == other.isNew && timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = filePath.hashCode()
        result = 31 * result + originalContent.contentHashCode()
        result = 31 * result + isNew.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/**
 * 变更记录 — 一次 Agent 操作产生的所有文件变更。
 *
 * @param title     标题，如 "对 xxx 需求的变更"
 * @param timestamp 变更时间
 * @param changes   本次变更涉及的文件列表
 */
data class ChangeRecord(
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val changes: MutableList<FileChangeInfo> = mutableListOf()
) {
    /** 格式化的时间字符串 */
    fun formattedTime(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}
