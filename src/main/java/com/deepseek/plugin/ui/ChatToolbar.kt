package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * A compact toolbar at the top of the chat panel with IntelliJ-style action buttons.
 *
 * Layout (right-aligned):
 * ┌──────────────────────────────────────┐
 * │                              [≡] [⏱] │
 * └──────────────────────────────────────┘
 *
 * @param onShowUsage   Called when the usage button is clicked.
 * @param onShowHistory Called when the history button is clicked.
 */
class ChatToolbar(
    onShowUsage: () -> Unit,
    onShowHistory: () -> Unit
) : JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)) {

    init {
        border = CompoundBorder(
            JBUI.Borders.empty(1, 4, 1, 4),
            JBUI.Borders.empty()
        )
        isOpaque = false

        add(createActionButton(
            icon = AllIcons.Actions.Profile,
            tooltip = "用量查看",
            onClick = onShowUsage
        ))
        add(createActionButton(
            icon = AllIcons.Actions.Find,
            tooltip = "会话历史",
            onClick = onShowHistory
        ))
    }

    private fun createActionButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JPanel {
        val presentation = Presentation().apply {
            this.icon = icon
            this.description = tooltip
        }
        val action = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                onClick()
            }
        }
        val button = ActionButton(action, presentation, ActionPlaces.TOOLBAR, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
        return button.withTooltip(tooltip)
    }
}
