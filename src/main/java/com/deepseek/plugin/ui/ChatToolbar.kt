package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingConstants

/**
 * A compact toolbar at the top of the chat panel with action buttons.
 *
 * Layout:
 * ┌──────────────────────────────────────┐
 * │                        [👀 用量] [⏰ 历史] │
 * └──────────────────────────────────────┘
 * ────────────────────────────────────────  ← JSeparator
 *
 * @param onShowUsage  Called when the usage button is clicked.
 * @param onShowHistory Called when the history button is clicked.
 */
class ChatToolbar(
    onShowUsage: () -> Unit,
    onShowHistory: () -> Unit
) : JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)) {

    init {
        border = JBUI.Borders.empty(1, 4, 1, 4)
        isOpaque = false

        add(createToolButton("👀", "用量查看", onShowUsage))
        add(createToolButton("⏰", "会话历史", onShowHistory))
    }

    private fun createToolButton(text: String, tooltip: String, action: () -> Unit): JButton {
        return object : JButton(text) {
            override fun getToolTipLocation(e: MouseEvent?): java.awt.Point? {
                return java.awt.Point(0, height + 2)
            }
        }.apply {
            this.toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            font = font.deriveFont(13f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(3, 6, 3, 6)
            addActionListener { action() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isOpaque = true
                    background = JBColor(0xE8E8E8, 0x4A4A4A)
                }
                override fun mouseExited(e: MouseEvent) {
                    isOpaque = false
                }
            })
        }
    }
}
