package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * A compact toolbar at the top of the chat panel with toolbar-style buttons.
 *
 * Layout:
 * ┌──────────────────────────────────────────────┐
 * │ [⏱] [≡]                        [🕐] [⚙]    │
 * └──────────────────────────────────────────────┘
 *
 * @param onShowUsage              Called when the usage button is clicked.
 * @param onShowHistory            Called when the history button is clicked.
 * @param onShowSettings           Called when the settings button is clicked.
 * @param onShowChangeManagement   Called when the change management button is clicked.
 */
class ChatToolbar(
    onShowUsage: () -> Unit,
    onShowHistory: () -> Unit,
    onShowSettings: () -> Unit,
    onShowChangeManagement: () -> Unit = {}
) : JPanel(BorderLayout()) {

    init {
        border = CompoundBorder(
            JBUI.Borders.empty(1, 4, 1, 4),
            JBUI.Borders.empty()
        )
        isOpaque = false

        // ── 左侧按钮区：用量查看 + 会话历史 ──
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        leftPanel.add(createToolbarButton(
            icon = AllIcons.Actions.Profile,
            tooltip = "用量查看",
            onClick = onShowUsage
        ))
        leftPanel.add(createToolbarButton(
            icon = AllIcons.Actions.Find,
            tooltip = "会话历史",
            onClick = onShowHistory
        ))
        add(leftPanel, BorderLayout.WEST)

        // ── 右侧按钮区：变更管理 + 技能设置 ──
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        rightPanel.add(createToolbarButton(
            icon = AllIcons.Vcs.History,
            tooltip = "变更管理",
            onClick = onShowChangeManagement
        ))
        rightPanel.add(createToolbarButton(
            icon = AllIcons.General.Settings,
            tooltip = "技能设置",
            onClick = onShowSettings
        ))
        add(rightPanel, BorderLayout.EAST)
    }
}
