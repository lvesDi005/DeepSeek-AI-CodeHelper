package com.deepseek.plugin.agent

import com.deepseek.plugin.chat.ChatPanel
import com.deepseek.plugin.i18n.I18n
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * 打开主题设置页面，方便用户设置右键输出语言。
 */
class SetLanguageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
        e.presentation.text = I18n.tr("agent.menu.setlang")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 激活工具窗口
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("DeepSeek AI CodeHelper")
        toolWindow?.activate(null)

        // 导航到主题设置页
        ChatPanel.currentInstance?.showSettingsPage("themeSettings")
    }
}
