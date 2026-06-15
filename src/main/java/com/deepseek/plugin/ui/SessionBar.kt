package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
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
 * ┌──────────────────────────────────────────────┐
 * │ [▼ 会话 1]  [+]  [🗑]              [✕]      │
 * └──────────────────────────────────────────────┘
 *
 * @param sessionComboBox   The combo box with session names.
 * @param onNewSession      Called when "新建会话" is clicked.
 * @param onClearAll        Called when "清除所有" is clicked.
 * @param onClearCurrent    Called when "清除本会话" is clicked.
 */
class SessionBar(
    private val sessionComboBox: JComboBox<String>,
    onNewSession: () -> Unit,
    onClearAll: () -> Unit,
    onClearCurrent: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(3, 6, 3, 6)

        // Left: session combo + new session + clear all
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(sessionComboBox)
            add(Box.createHorizontalStrut(4))
            add(createActionButton(
                icon = AllIcons.General.Add,
                tooltip = "新建会话",
                onClick = onNewSession
            ))
            add(Box.createHorizontalStrut(2))
            add(createActionButton(
                icon = AllIcons.Actions.GC,
                tooltip = "清除所有会话",
                onClick = onClearAll
            ))
        }
        add(leftPanel, BorderLayout.WEST)

        // Right: clear current session
        val clearBtn = createActionButton(
            icon = AllIcons.Actions.Close,
            tooltip = "清除当前会话",
            onClick = onClearCurrent
        )
        add(clearBtn, BorderLayout.EAST)
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
