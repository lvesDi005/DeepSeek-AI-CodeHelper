package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder

/**
 * A panel that displays attached file cards in a horizontal flow layout.
 * Each card shows a file type icon, file name, size, and a remove button.
 *
 * Layout:
 * ┌──────────────────────────────────────────────┐
 * │ ┌──────────┐  ┌──────────┐                   │
 * │ │ 📄       │  │ 📄       │                   │
 * │ │ app.java │  │ data.xml │                   │
 * │ │ 2.3 KB ✕ │  │ 1.1 KB ✕ │                   │
 * │ └──────────┘  └──────────┘                   │
 * └──────────────────────────────────────────────┘
 */
class FileAttachmentPreview(
    private val files: List<AttachedFile>,
    private val onRemove: (Int) -> Unit
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        background = JBColor(0xF8F9FA, 0x2A2A2A)
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor(0xDADCE0, 0x444444), 1, 1, 0, 1),
            JBUI.Borders.empty(4, 6, 4, 6)
        )

        for ((index, file) in files.withIndex()) {
            add(createFileCard(file, index))
            add(Box.createHorizontalStrut(6))
        }

        add(Box.createHorizontalGlue())
    }

    private fun createFileCard(file: AttachedFile, index: Int): JPanel {
        val cardBg = JBColor(0xFFFFFF, 0x3C3C3C)
        val card = JPanel(BorderLayout())
        card.background = cardBg
        card.border = BorderFactory.createLineBorder(JBColor(0xE0E0E0, 0x555555), 1)
        card.preferredSize = Dimension(160, 52)
        card.maximumSize = Dimension(160, 52)
        card.minimumSize = Dimension(120, 48)

        // Icon (left)
        val icon = getFileTypeIcon(file.name)
        val iconLabel = JLabel(icon)
        iconLabel.verticalAlignment = SwingConstants.CENTER
        iconLabel.border = EmptyBorder(0, 6, 0, 4)
        card.add(iconLabel, BorderLayout.WEST)

        // Name + size (center)
        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }

        val nameLabel = JLabel(truncateFileName(file.name, 16))
        nameLabel.font = nameLabel.font.deriveFont(Font.PLAIN, 11f)
        nameLabel.foreground = JBColor(0x333333, 0xDDDDDD)
        nameLabel.toolTipText = file.name
        infoPanel.add(nameLabel)

        val sizeLabel = JLabel(file.sizeDisplay)
        sizeLabel.font = sizeLabel.font.deriveFont(Font.PLAIN, 10f)
        sizeLabel.foreground = JBColor(0x888888, 0x999999)
        infoPanel.add(sizeLabel)

        card.add(infoPanel, BorderLayout.CENTER)

        // Remove button (right)
        val removeBtn = object : JButton("\u2715") {
            override fun getToolTipLocation(e: MouseEvent?): java.awt.Point? {
                return java.awt.Point(-width, height + 2)
            }
        }.apply {
            toolTipText = "移除 ${file.name}"
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            font = font.deriveFont(9f)
            foreground = JBColor(0xAAAAAA, 0x888888)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(2, 4, 2, 4)
            addActionListener { onRemove(index) }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    foreground = JBColor(0x333333, 0xFFFFFF)
                }
                override fun mouseExited(e: MouseEvent) {
                    foreground = JBColor(0xAAAAAA, 0x888888)
                }
            })
        }

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        actionsPanel.isOpaque = false
        actionsPanel.add(removeBtn)
        card.add(actionsPanel, BorderLayout.EAST)

        return card
    }

    private fun getFileTypeIcon(fileName: String): javax.swing.Icon {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> AllIcons.FileTypes.Java
            "xml" -> AllIcons.FileTypes.Xml
            "json" -> AllIcons.FileTypes.Json
            "yaml", "yml" -> AllIcons.FileTypes.Yaml
            "html", "htm" -> AllIcons.FileTypes.Html
            "css" -> AllIcons.FileTypes.Css
            "js" -> AllIcons.FileTypes.JavaScript
            "txt", "md" -> AllIcons.FileTypes.Text
            "png", "jpg", "jpeg", "gif", "svg", "bmp" -> AllIcons.FileTypes.Image
            "zip", "jar" -> AllIcons.FileTypes.Archive
            else -> AllIcons.FileTypes.Any_type
        }
    }

    private fun truncateFileName(name: String, maxLen: Int): String {
        return if (name.length <= maxLen) name
        else name.take(maxLen - 3) + "..." + name.substringAfterLast('.')
    }
}
