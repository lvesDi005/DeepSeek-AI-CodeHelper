package com.deepseek.plugin.completion

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * 快速切换 AI 补全启用/禁用。
 * 默认快捷键：Ctrl+Shift+,（需用户在 Keymap 中绑定）。
 */
class ToggleCompletionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val settings = DeepSeekSettings.instance
        settings.completionEnabled = !settings.completionEnabled
        val status = if (settings.completionEnabled) "enabled" else "disabled"
        // 通过状态栏提示（不弹对话框，避免打扰）
        val project = e.project ?: return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).notifyByBalloon(
            "DeepSeek AI CodeHelper",
            com.intellij.openapi.ui.MessageType.INFO,
            "AI Completion $status"
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        val settings = DeepSeekSettings.instance
        e.presentation.text = if (settings.completionEnabled)
            "Disable AI Completion" else "Enable AI Completion"
    }
}
