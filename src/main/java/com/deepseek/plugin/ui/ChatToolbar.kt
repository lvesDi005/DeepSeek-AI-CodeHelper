package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * A compact toolbar at the top of the chat panel with toolbar-style buttons.
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

        add(createToolbarButton(
            icon = AllIcons.Actions.Profile,
            tooltip = "用量查看",
            onClick = onShowUsage
        ))
        add(createToolbarButton(
            icon = AllIcons.Actions.Find,
            tooltip = "会话历史",
            onClick = onShowHistory
        ))
    }
}
