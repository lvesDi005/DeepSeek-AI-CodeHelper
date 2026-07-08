package com.deepseek.plugin.agent

import com.deepseek.plugin.chat.ChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 将控制台选中的文本"上传"到聊天面板，以选中代码标签形式显示在输入框上方。
 *
 * 用户可在 Run/Debug Console 中选中文本 → 右键 → DeepSeek AI → Upload to Chat，
 * 选中文本会以 "Console Output: 1-XX" 标签形式出现在聊天输入框上方，
 * 用户随后可在聊天面板中提问，AI 将结合该上下文作答。
 */
class UploadConsoleAction : AnAction() {

    /**
     * 使用 EDT 线程模型以确保 [CommonDataKeys.EDITOR] 等 UI 数据在控制台上下文中可访问。
     * BGT 线程下获取控制台编辑器可能返回 null。
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 获取选中文本 — EDT 线程下 CommonDataKeys.EDITOR 在控制台中也可用
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText ?: return
        if (selectedText.isBlank()) return

        // 激活 DeepSeek AI 聊天面板（回调确保面板初始化完成后才推送内容）
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("DeepSeek AI CodeHelper")
        toolWindow?.activate {
            // 优先使用 currentInstance，兜底从 toolWindow 内容查找
            val panel = ChatPanel.currentInstance ?: findChatPanel(toolWindow)
            panel?.let {
                it.setConsoleContext(selectedText)
                it.focusInput()
            }
        }
    }

    /**
     * 从 ToolWindow 的内容中查找 [ChatPanel] 实例。
     * 当 [ChatPanel.currentInstance] 为 null 时兜底使用
     *（如工具窗口关闭后重新激活时可能存在时序间隙）。
     */
    private fun findChatPanel(toolWindow: ToolWindow): ChatPanel? {
        for (i in 0 until toolWindow.contentManager.contentCount) {
            val content = toolWindow.contentManager.getContent(i) ?: continue
            if (content.component is ChatPanel) {
                return content.component as ChatPanel
            }
        }
        return null
    }
}
