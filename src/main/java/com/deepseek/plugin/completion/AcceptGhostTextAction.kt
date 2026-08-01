package com.deepseek.plugin.completion

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import java.awt.event.KeyEvent

/**
 * 接受 Ghost Text 补全的动作。
 *
 * 当编辑器中有 [GhostTextManager] 管理的 Ghost Text 时，
 * 按 Tab（或自定义快捷键）将其内容写入文档。
 *
 * 没有 Ghost Text 时，通过向编辑器组件分发 Tab 键事件来触发默认行为
 *（缩进 / 代码补全），避免直接调用其他 AnAction（违反 OverrideOnly API 约束）。
 */
class AcceptGhostTextAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (GhostTextManager.acceptGhostText(editor)) {
            // Ghost Text 被接收，不需要执行默认行为
            return
        }
        // 没有 Ghost Text 时，向编辑器组件分发 Tab 键事件以触发默认行为
        dispatchTabKey(editor)
    }

    private fun dispatchTabKey(editor: Editor) {
        val component = editor.component
        val now = System.currentTimeMillis()
        val pressed = KeyEvent(
            component, KeyEvent.KEY_PRESSED, now, 0,
            KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED
        )
        val released = KeyEvent(
            component, KeyEvent.KEY_RELEASED, now + 1, 0,
            KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED
        )
        component.dispatchEvent(pressed)
        component.dispatchEvent(released)
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
