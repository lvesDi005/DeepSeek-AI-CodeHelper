package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * The session management bar below the toolbar.
 *
 * Layout:
 * ┌──────────────────────────────────────┐
 * │ [▼ 会话 1]  [+]  [🗑]               │
 * └──────────────────────────────────────┘
 *
 * @param sessionComboBox   The combo box with session names.
 * @param onNewSession      Called when "新建会话" is clicked.
 * @param onClearCurrent    Called when "清除当前会话" is clicked.
 */
class SessionBar(
    private val sessionComboBox: JComboBox<String>,
    onNewSession: () -> Unit,
    onClearCurrent: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(3, 6, 3, 6)

        // Left: session combo + new session + clear current
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(sessionComboBox)
            add(Box.createHorizontalStrut(4))
            add(createToolbarButton(
                icon = AllIcons.General.Add,
                tooltip = I18n.tr("session.new"),
                onClick = onNewSession
            ))
            add(Box.createHorizontalStrut(2))
            add(createToolbarButton(
                icon = AllIcons.Actions.GC,
                tooltip = I18n.tr("session.clear.current"),
                onClick = onClearCurrent
            ))
        }
        add(leftPanel, BorderLayout.WEST)
    }
}
