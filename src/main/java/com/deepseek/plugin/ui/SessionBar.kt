package com.deepseek.plugin.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * The session management bar below the toolbar.
 *
 * Layout:
 * ┌──────────────────────────────────────────────┐
 * │ [▼ 会话 1] [+ 新建] [🗑 清除所有]     [清除本会话] │
 * └──────────────────────────────────────────────┘
 * ────────────────────────────────────────────────  ← JSeparator
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
        border = JBUI.Borders.empty(2, 4, 2, 4)

        // Left: session combo + new session + clear all
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(sessionComboBox)
            add(createSessionButton("+ 新建会话", onNewSession))
            add(Box.createHorizontalStrut(2))
            add(createSessionButton("🗑 清除所有", onClearAll))
        }
        add(leftPanel, BorderLayout.WEST)

        // Right: clear current session
        add(createSessionButton("清除本会话", onClearCurrent).apply {
            foreground = java.awt.Color(180, 80, 80)
        }, BorderLayout.EAST)
    }

    private fun createSessionButton(text: String, onClick: () -> Unit): JButton {
        return object : JButton(text) {
            override fun getToolTipLocation(e: MouseEvent?): java.awt.Point? {
                return java.awt.Point(0, height + 2)
            }
        }.apply {
            this.toolTipText = text
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            font = font.deriveFont(Font.PLAIN, 11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(4, 8, 4, 8)
            addActionListener { onClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isOpaque = true
                    background = com.intellij.ui.JBColor(0xE0E0E0, 0x4A4A4A)
                }
                override fun mouseExited(e: MouseEvent) {
                    isOpaque = false
                }
            })
        }
    }
}
