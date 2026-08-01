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
import java.awt.Image
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import com.deepseek.plugin.i18n.I18n

/**
 * A panel that displays attached file cards in a horizontal flow layout.
 * Each card shows a file type icon, file name, size, and a remove button.
 *
 * Layout:
 * ┌──────────────────────────────────────────────┐
 * │ ┌──────────┐  ┌──────────┐                   │
 * │ │ image    │  │ 📄       │                   │
 * │ │ demo.png │  │ app.java │                   │
 * │ │ 2.3 KB ✕ │  │ 2.3 KB ✕ │                   │
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
        border = JBUI.Borders.empty(4, 6, 4, 6)

        for ((index, file) in files.withIndex()) {
            add(createFileCard(file, index))
            add(Box.createHorizontalStrut(6))
        }

        add(Box.createHorizontalGlue())
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }

    private fun createFileCard(file: AttachedFile, index: Int): JPanel {
        val cardBg = JBColor(0xFFFFFF, 0x3C3C3C)
        val radius = 8
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = cardBg
                g2.fillRoundRect(0, 0, width, height, radius, radius)
                g2.color = JBColor(0xE0E0E0, 0x555555)
                g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
                g2.dispose()
                super.paintComponent(g)
            }
        }
        card.preferredSize = Dimension(160, 52)
        card.maximumSize = Dimension(160, 52)
        card.minimumSize = Dimension(120, 48)
        card.isOpaque = false

        // Icon (left) — thumbnail for images, generic icon otherwise
        val icon = getPreviewIcon(file)
        val iconLabel = if (isImageFile(file.name)) {
            // JLabel with custom tooltip showing full image
            object : JLabel(icon) {
                override fun createToolTip(): javax.swing.JToolTip {
                    val fullIcon = ImageIcon(file.absolutePath)
                    val maxShowW = 600
                    val maxShowH = 400
                    val scale = minOf(maxShowW.toDouble() / fullIcon.iconWidth, maxShowH.toDouble() / fullIcon.iconHeight, 1.0)
                    val sw = (fullIcon.iconWidth * scale).toInt().coerceAtLeast(1)
                    val sh = (fullIcon.iconHeight * scale).toInt().coerceAtLeast(1)
                    val scaledIcon = ImageIcon(fullIcon.image.getScaledInstance(sw, sh, Image.SCALE_SMOOTH))

                    val tip = javax.swing.JToolTip()
                    tip.component = this
                    tip.layout = BorderLayout()
                    tip.add(JLabel(scaledIcon))
                    tip.preferredSize = Dimension(sw, sh)
                    return tip
                }
            }.apply { toolTipText = file.name }
        } else {
            JLabel(icon)
        }
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
        nameLabel.foreground = JBColor(0x000000, 0xDDDDDD)
        nameLabel.toolTipText = file.name
        infoPanel.add(nameLabel)

        val sizeLabel = JLabel(file.sizeDisplay)
        sizeLabel.font = sizeLabel.font.deriveFont(Font.PLAIN, 10f)
        sizeLabel.foreground = JBColor(0x000000, 0xBBBBBB)
        infoPanel.add(sizeLabel)

        card.add(infoPanel, BorderLayout.CENTER)

        // Remove button (right)
        val removeBtn = createToolbarButton(
            icon = AllIcons.Actions.Close,
            tooltip = I18n.tr("file.remove") + " " + file.name,
            tooltipKey = "file.remove",
            size = 16,
            onClick = { onRemove(index) }
        )

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        actionsPanel.isOpaque = false
        actionsPanel.add(removeBtn)
        card.add(actionsPanel, BorderLayout.EAST)

        return card
    }

    private fun getPreviewIcon(file: AttachedFile): Icon {
        if (isImageFile(file.name)) {
            createImageThumbnail(file.absolutePath, 48, 36)?.let { return it }
        }
        return getFileTypeIcon(file.name)
    }

    private fun createImageThumbnail(path: String, maxW: Int, maxH: Int): Icon? {
        return try {
            val icon = ImageIcon(path)
            var w = icon.iconWidth
            var h = icon.iconHeight
            if (w <= 0 || h <= 0) return null
            val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h, 1.0)
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
            ImageIcon(icon.image.getScaledInstance(w, h, Image.SCALE_SMOOTH))
        } catch (_: Exception) {
            null
        }
    }

    private fun getFileTypeIcon(fileName: String): Icon {
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
