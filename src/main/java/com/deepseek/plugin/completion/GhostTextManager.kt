package com.deepseek.plugin.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.util.concurrent.ConcurrentHashMap

/**
 * Ghost Text 管理器。
 *
 * 管理编辑器中的 Ghost Text Inlay 生命周期：
 * - 显示/隐藏 Ghost Text
 * - 接受 Ghost Text（将内容写入文档）
 * - 每个编辑器维护独立的 Ghost Text 状态
 *
 * 线程安全：使用 ConcurrentHashMap 存储按编辑器索引的 GhostTextSession。
 */
object GhostTextManager {

    private val GHOST_KEY = Key.create<GhostTextSession>("DeepSeekGhostText")
    private val sessions = ConcurrentHashMap<Editor, GhostTextSession>()

    /** Ghost Text 会话状态 */
    data class GhostTextSession(
        val text: String,
        val inlineInlay: Inlay<*>?,
        val blockInlay: Inlay<*>?,
        var active: Boolean = true
    )

    /**
     * 在编辑器中显示 Ghost Text。
     *
     * @param editor 目标编辑器
     * @param text 要显示的补全文本
     * @param offset 插入位置（光标偏移）
     */
    fun showGhostText(editor: Editor, text: String, offset: Int) {
        if (text.isBlank()) return

        // 清除已有的 Ghost Text
        dismissGhostText(editor)

        try {
            val lines = text.lines()
            val renderer = GhostTextRenderer(text)

            // 第一行作为 inline inlay
            val inlineInlay = editor.inlayModel.addInlineElement(offset, false, renderer)

            // 后续行作为 block inlay
            val blockInlay = if (lines.size > 1) {
                val logicalPos = editor.offsetToLogicalPosition(offset)
                val nextLinePos = LogicalPosition(logicalPos.line + 1, 0)
                val nextLineOffset = editor.logicalPositionToOffset(nextLinePos)
                editor.inlayModel.addBlockElement(nextLineOffset, false, false, 0, renderer)
            } else null

            val session = GhostTextSession(
                text = text,
                inlineInlay = inlineInlay,
                blockInlay = blockInlay,
                active = true
            )
            sessions[editor] = session
            editor.putUserData(GHOST_KEY, session)
        } catch (_: Exception) {
            // Inlay 创建失败时静默处理
        }
    }

    /**
     * 隐藏并清理 Ghost Text。
     */
    fun dismissGhostText(editor: Editor) {
        val session = sessions.remove(editor) ?: return
        editor.putUserData(GHOST_KEY, null)
        session.active = false

        try {
            session.inlineInlay?.let { Disposer.dispose(it) }
            session.blockInlay?.let { Disposer.dispose(it) }
        } catch (_: Exception) {
            // 如果 Inlay 已经被 dispose，静默处理
        }
    }

    /**
     * 接受 Ghost Text：将补全文本写入编辑器文档。
     *
     * @return 是否成功接受
     */
    fun acceptGhostText(editor: Editor): Boolean {
        val session = sessions[editor] ?: return false
        if (!session.active || session.text.isBlank()) return false

        val caretOffset = editor.caretModel.offset

        // 使用 WriteCommandAction 写入文档
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(editor.project) {
            try {
                val document = editor.document
                document.insertString(caretOffset, session.text)
                // 移动光标到补全末尾
                editor.caretModel.moveToOffset(caretOffset + session.text.length)
            } catch (_: Exception) {
                // 写入失败时静默处理
            }
        }

        dismissGhostText(editor)
        return true
    }

    /**
     * 检查当前编辑器是否有激活的 Ghost Text。
     */
    fun hasActiveGhostText(editor: Editor): Boolean {
        return sessions[editor]?.active == true
    }

    /**
     * 获取当前激活的 Ghost Text 文本。
     */
    fun getActiveText(editor: Editor): String? {
        return sessions[editor]?.takeIf { it.active }?.text
    }
}
