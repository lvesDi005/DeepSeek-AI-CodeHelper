package com.deepseek.plugin.ui

import com.deepseek.plugin.PluginVersion
import com.deepseek.plugin.i18n.I18n
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Empty-state welcome panel shown when the chat has no messages yet.
 *
 * Displays the plugin logo, name, a short description, and useful keyboard shortcuts
 * to help users get started. Uses robot.png from plugin resources.
 */
class WelcomePanel : JPanel(BorderLayout()) {

    /** Robot icon scaled to a reasonable display size. */
    private val pluginIcon: Icon? = run {
        try {
            val url = WelcomePanel::class.java.getResource("/robot.png")
            if (url != null) {
                val original = ImageIcon(url)
                // Scale to a nice display size (max 120px wide)
                val scaleW = minOf(original.iconWidth, 120)
                val scaleH = (original.iconHeight * scaleW) / original.iconWidth
                ImageIcon(original.image.getScaledInstance(scaleW, scaleH, Image.SCALE_SMOOTH))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    init {
        isOpaque = false
        add(createCenterPanel(), BorderLayout.CENTER)
    }

    private fun createCenterPanel(): JPanel {
        val center = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
        }

        // ── Plugin icon (SVG) ──
        if (pluginIcon != null) {
            val iconLabel = JLabel(pluginIcon).apply {
                alignmentX = Component.CENTER_ALIGNMENT
            }
            content.add(iconLabel)
            content.add(Box.createVerticalStrut(12))
        }

        // ── Plugin name ──
        val nameLabel = JLabel(I18n.tr("welcome.title")).apply {
            font = font.deriveFont(Font.BOLD, 18f)
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(nameLabel)
        content.add(Box.createVerticalStrut(6))

        // ── Tagline ──
        val tagline = JLabel(I18n.tr("welcome.subtitle")).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x888888, 0x999999)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(tagline)
        content.add(Box.createVerticalStrut(24))

        // ── Quick tips section ──
        val tipsTitle = JLabel(I18n.tr("welcome.quickstart")).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = JBColor(0x333333, 0xBBBBBB)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(tipsTitle)
        content.add(Box.createVerticalStrut(10))

        val tips = listOf(
            TipEntry("Enter", I18n.tr("send")),
            TipEntry("Shift + Enter", I18n.tr("newline")),
            TipEntry("选中代码", I18n.tr("autoFill")),
            TipEntry("上传文件", I18n.tr("supportedFiles")),
            TipEntry("💬 Q&A / 🤖 Agent", I18n.tr("modeSwitch")),
            TipEntry("清除会话", I18n.tr("clearSession")),
        )

        for ((i, tip) in tips.withIndex()) {
            content.add(createTipRow(tip))
            if (i < tips.lastIndex) {
                content.add(Box.createVerticalStrut(4))
            }
        }

        content.add(Box.createVerticalStrut(20))

        // ── Version info ──
        val versionLabel = JLabel(I18n.tr("welcome.version") + "${PluginVersion.current}").apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBColor(0xAAAAAA, 0x666666)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        content.add(versionLabel)

        center.add(content)
        return center
    }

    private fun createTipRow(tip: TipEntry): JPanel {
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val keyLabel = JLabel(tip.shortcut).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(0x1A73E8, 0x64B5F6)
            border = JBUI.Borders.empty(1, 6)
        }
        row.add(keyLabel)
        row.add(Box.createHorizontalStrut(8))

        val descLabel = JLabel(tip.description).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x555555, 0xAAAAAA)
        }
        row.add(descLabel)

        return row
    }

    private data class TipEntry(val shortcut: String, val description: String)
}
