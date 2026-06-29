package com.deepseek.plugin.agent

import com.deepseek.plugin.chat.ChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 将控制台选中的文本"上传"到聊天面板，以选中代码标签形式显示在输入框上方。
 *
 * 用户可在 Run/Debug Console 中选中文本 → 右键 → DeepSeek AI → Upload to Chat，
 * 选中文本会以 "Console Output: 1-XX" 标签形式出现在聊天输入框上方，
 * 用户随后可在聊天面板中提问，AI 将结合该上下文作答。
 */
class UploadConsoleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 获取选中文本
        val editor = e.getData(CommonDataKeys.EDITOR)
        val selectedText = editor?.selectionModel?.selectedText ?: return
        if (selectedText.isBlank()) return

        // 激活 DeepSeek AI 聊天面板（回调确保面板初始化完成后才推送内容）
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("DeepSeek AI CodeHelper")
        toolWindow?.activate {
            ChatPanel.currentInstance?.let { panel ->
                panel.setConsoleContext(selectedText)
                panel.focusInput()
            }
        }
    }
}
