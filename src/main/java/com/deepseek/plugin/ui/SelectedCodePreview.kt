package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * A compact card shown above the chat input that previews a selected code range.
 *
 * Layout:
 * ┌────────────────────────────────────────────┐
 * │  📄 ChatPanel.kt:174-221               ✕  │  ← Header row
 * ├────────────────────────────────────────────┤
 * │  private fun fillInputFromSelection()...    │  ← Optional snippet (max 3 lines)
 * └────────────────────────────────────────────┘
 */
class SelectedCodePreview(
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    snippet: String? = null,
    onDismiss: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        val bg = JBColor(0xF0F4FF, 0x253341)
        background = bg
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor(0xC5D5F0, 0x3A4A5A), 1, 1, 0, 1),
            JBUI.Borders.empty(0, 0, 0, 0)
        )

        // ── Header row: icon + file info (left), dismiss button (right) ──
        add(createHeader(bg, onDismiss), BorderLayout.NORTH)

        // ── Optional snippet ──
        if (snippet != null && snippet.isNotBlank()) {
            add(createSnippetArea(snippet, bg), BorderLayout.CENTER)
        }
    }

    private fun createHeader(bg: java.awt.Color, onDismiss: () -> Unit): JPanel {
        val header = JPanel(BorderLayout())
        header.background = bg
        header.isOpaque = false
        header.border = JBUI.Borders.empty(3, 8, 3, 4)

        // File info (left)
        val infoLabel = JLabel("$fileName: $startLine-$endLine")
        infoLabel.font = infoLabel.font.deriveFont(Font.BOLD, 11f)
        infoLabel.foreground = JBColor(0x1A73E8, 0x64B5F6)
        infoLabel.icon = AllIcons.FileTypes.Any_type
        header.add(infoLabel, BorderLayout.WEST)

        // Dismiss button (right)
        val dismissBtn = ActionButton(
            object : AnAction(null, null, AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    onDismiss()
                }
            },
            Presentation().apply {
                this.icon = AllIcons.Actions.Close
                this.description = "移除选中代码引用"
            },
            ActionPlaces.TOOLBAR,
            Dimension(16, 16)
        ).withTooltip("移除选中代码引用")

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        actionsPanel.isOpaque = false
        actionsPanel.add(dismissBtn)
        header.add(actionsPanel, BorderLayout.EAST)

        return header
    }

    private fun createSnippetArea(snippet: String, bg: java.awt.Color): JBTextArea {
        // Show first 3 lines max, truncated
        val lines = snippet.lines()
        val displayText = if (lines.size > 3) {
            lines.take(3).joinToString("\n") + "\n..."
        } else {
            snippet
        }

        return JBTextArea(displayText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 11)
            background = bg
            foreground = JBColor(0x555555, 0xBBBBBB)
            margin = JBUI.insets(0, 22, 4, 8)
            border = JBUI.Borders.empty()
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 60)
        }
    }
}
