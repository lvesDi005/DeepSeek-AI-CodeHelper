package com.deepseek.plugin.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBDimension
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Modal dialog displaying the plugin's version update changelog.
 *
 * Shown when the user opens the tool window after installing or updating
 * the plugin to a new version.
 */
class ChangelogDialog(
    private val previousVersion: String?,
    private val currentVersion: String,
    private val changeLogHtml: String
) : DialogWrapper(true) {

    init {
        title = "DeepSeek AI CodeHelper — 更新记录"
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = JBDimension(480, 400)
        }

        // ── Header ──
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val titleLabel = JLabel("🚀 DeepSeek AI CodeHelper 更新").apply {
            font = font.deriveFont(Font.BOLD, 16f)
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createVerticalStrut(4))

        val versionLabel = JLabel(
            if (previousVersion != null)
                "$previousVersion → $currentVersion"
            else
                "当前版本: $currentVersion"
        ).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x888888, 0x999999)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerPanel.add(versionLabel)
        headerPanel.add(Box.createVerticalStrut(12))

        root.add(headerPanel, BorderLayout.NORTH)

        // ── Changelog content ──
        val changelogPane = JEditorPane("text/html", wrapChangelogHtml(changeLogHtml)).apply {
            isEditable = false
            border = JBUI.Borders.empty(4)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = font.deriveFont(12f)
            caretPosition = 0
        }

        val scrollPane = JScrollPane(changelogPane).apply {
            border = JBUI.Borders.customLine(JBColor(0xDDDDDD, 0x444444), 1)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        root.add(scrollPane, BorderLayout.CENTER)

        return root
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction.apply {
            putValue(Action.NAME, "知道了")
        })
    }

    companion object {
        /**
         * Wrap the raw HTML changelog in a styled document.
         */
        private fun wrapChangelogHtml(rawHtml: String): String {
            val bgColor = if (JBColor.isBright()) "#FFFFFF" else "#2B2B2B"
            val fgColor = if (JBColor.isBright()) "#1A1A1A" else "#E0E0E0"
            val headingColor = if (JBColor.isBright()) "#333333" else "#BBBBBB"
            val codeBg = if (JBColor.isBright()) "#F0F0F0" else "#3A3A3A"

            return """<html>
    <head>
        <style>
            body {
                font-family: 'Segoe UI', Roboto, sans-serif;
                font-size: 12pt;
                color: $fgColor;
                background-color: $bgColor;
                padding: 8px;
                margin: 0;
            }
            h3 { color: $headingColor; font-size: 14pt; margin: 14px 0 6px 0; font-weight: bold; }
            h4 { color: $headingColor; font-size: 12pt; margin: 10px 0 4px 0; font-weight: bold; }
            ul { margin: 4px 0 8px 0; padding-left: 20px; }
            li { margin: 3px 0; line-height: 1.4; }
            code {
                font-family: 'JetBrains Mono', Monospaced, monospace;
                font-size: 11pt;
                background-color: $codeBg;
                padding: 1px 4px;
                border-radius: 3px;
            }
        </style>
    </head>
    <body>$rawHtml</body>
</html>""".trimIndent()
        }
    }
}
