package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
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
 * ┌──────────────────────────────────────────────────────┐
 * │                                        [≡] [🕐] [⚙] │
 * └──────────────────────────────────────────────────────┘
 *
 * @param onShowHistory            Called when the history button is clicked.
 * @param onShowSettings           Called when the settings button is clicked.
 * @param onShowChangeManagement   Called when the change management button is clicked.
 */
class ChatToolbar(
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

        // ── 右侧按钮区：会话历史 + 变更管理 + 技能设置 ──
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        rightPanel.add(createToolbarButton(
            icon = AllIcons.Actions.Find,
            tooltip = I18n.tr("toolbar.history"),
            tooltipKey = "toolbar.history",
            onClick = onShowHistory
        ))
        rightPanel.add(createToolbarButton(
            icon = AllIcons.Vcs.History,
            tooltip = I18n.tr("toolbar.change.management"),
            tooltipKey = "toolbar.change.management",
            onClick = onShowChangeManagement
        ))
        rightPanel.add(createToolbarButton(
            icon = AllIcons.General.Settings,
            tooltip = I18n.tr("toolbar.settings"),
            tooltipKey = "toolbar.settings",
            onClick = onShowSettings
        ))
        add(rightPanel, BorderLayout.EAST)
    }
}
