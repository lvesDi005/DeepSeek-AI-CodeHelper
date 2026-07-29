package com.deepseek.plugin.completion

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware

/**
 * 接受 Ghost Text 补全的动作。
 *
 * 当编辑器中有 [GhostTextManager] 管理的 Ghost Text 时，
 * 按 Tab（或自定义快捷键）将其内容写入文档。
 *
 * 注册为 EditorTab action 的 handler 代理。
 */
class AcceptGhostTextAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (GhostTextManager.acceptGhostText(editor)) {
            // Ghost Text 被接收，不需要执行默认行为
            return
        }
        // 没有 Ghost Text 时，调用默认 Tab 行为
        val defaultTab = ActionManager.getInstance().getAction("EditorTab")
        if (defaultTab != null) {
            val inputEvent = e.getInputEvent()
            ActionManager.getInstance().tryToExecute(defaultTab, inputEvent, null, "EditorTab", false)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null && GhostTextManager.hasActiveGhostText(editor)) {
            e.presentation.isEnabledAndVisible = true
            e.presentation.text = "Accept DeepSeek Completion"
        } else {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
