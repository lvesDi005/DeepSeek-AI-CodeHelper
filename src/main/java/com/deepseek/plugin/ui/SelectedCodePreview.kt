package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel
import com.deepseek.plugin.i18n.I18n

/**
 * A compact badge shown above the chat input indicating the selected code range.
 *
 * Layout (all in one row):
 * ┌──────────────────────────────────┐
 * │ 📄 ChatPanel.kt:174-221       ✕ │
 * └──────────────────────────────────┘
 *
 * The panel sizes itself to fit its content; it does NOT stretch to full width.
 */
class SelectedCodePreview(
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    onDismiss: () -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

    init {
        isOpaque = false

        val bg = JBColor(0xF0F4FF, 0x253341)
        val borderColor = JBColor(0xC5D5F0, 0x3A4A5A)

        val chip = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 8, 1, 2)

            // File info label
            val infoLabel = JLabel("$fileName: $startLine-$endLine").apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = JBColor(0x1A73E8, 0x64B5F6)
                icon = AllIcons.FileTypes.Any_type
                iconTextGap = 4
            }
            add(infoLabel, BorderLayout.WEST)

            // Dismiss button
            val dismissBtn = createToolbarButton(
                icon = AllIcons.Actions.Close,
                tooltip = I18n.tr("selected.code.remove"),
                size = 16,
                onClick = onDismiss
            )

            val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(dismissBtn)
            }
            add(actionsPanel, BorderLayout.EAST)

            // Give it a reasonable max width for the chip
            maximumSize = Dimension(600, 30)
        }

        add(chip)
    }
}
