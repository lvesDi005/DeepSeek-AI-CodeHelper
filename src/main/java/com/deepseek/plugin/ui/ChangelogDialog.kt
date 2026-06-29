package com.deepseek.plugin.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.JBDimension
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

class ChangelogDialog(
    private val previousVersion: String?,
    private val currentVersion: String,
    private val changeLogHtml: String,
    private val changeLogHtmlEn: String,
    initialLanguage: String = "zh"
) : DialogWrapper(true) {

    /** The language selected by the user when the dialog closes. */
    var selectedLanguage: String = initialLanguage
        private set

    private val languageCombo = JComboBox(arrayOf("中文", "English"))
    private val changelogPane = JEditorPane("text/html", null).apply {
        isEditable = false
        border = JBUI.Borders.empty(4)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = font.deriveFont(12f)
    }

    init {
        isResizable = true

        // Set initial language selection
        languageCombo.selectedIndex = if (initialLanguage == "en") 1 else 0
        selectedLanguage = if (languageCombo.selectedIndex == 1) "en" else "zh"

        languageCombo.addActionListener {
            selectedLanguage = if (languageCombo.selectedIndex == 1) "en" else "zh"
            updateDialogLanguage()
            updateContent()
        }

        title = if (initialLanguage == "en") {
            "DeepSeek AI CodeHelper \u2014 Release Notes"
        } else {
            "DeepSeek AI CodeHelper \u2014 更新记录"
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
            preferredSize = JBDimension(480, 400)
        }

        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // ── Language selector row (right-aligned) ──
        val langRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 30)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        langRow.add(JLabel("🌐"), BorderLayout.WEST)
        val langRightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        langRightPanel.add(languageCombo)
        langRow.add(langRightPanel, BorderLayout.EAST)
        headerPanel.add(langRow)
        headerPanel.add(Box.createVerticalStrut(4))

        val titleLabel = JLabel().apply {
            font = font.deriveFont(Font.BOLD, 16f)
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        titleLabel.name = "titleLabel"
        headerPanel.add(titleLabel)
        headerPanel.add(Box.createVerticalStrut(4))

        val versionLabel = JLabel().apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x888888, 0x999999)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        versionLabel.name = "versionLabel"
        headerPanel.add(versionLabel)
        headerPanel.add(Box.createVerticalStrut(12))

        // Apply localized header labels
        applyHeaderLabels(titleLabel, versionLabel)
        // Update changelog content
        updateContent()

        root.add(headerPanel, BorderLayout.NORTH)

        val scrollPane = JScrollPane(changelogPane).apply {
            border = JBUI.Borders.customLine(JBColor(0xDDDDDD, 0x444444), 1)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        root.add(scrollPane, BorderLayout.CENTER)

        return root
    }

    override fun createActions(): Array<Action> {
        val okText = if (selectedLanguage == "en") "Got it" else "知道了"
        return arrayOf(okAction.apply {
            putValue(Action.NAME, okText)
        })
    }

    private fun updateContent() {
        val rawHtml = if (selectedLanguage == "en") changeLogHtmlEn else changeLogHtml
        changelogPane.text = wrapChangelogHtml(rawHtml)
        changelogPane.caretPosition = 0
    }

    private fun updateDialogLanguage() {
        val isEn = selectedLanguage == "en"
        title = if (isEn) {
            "DeepSeek AI CodeHelper \u2014 Release Notes"
        } else {
            "DeepSeek AI CodeHelper \u2014 更新记录"
        }
        okAction.putValue(Action.NAME, if (isEn) "Got it" else "知道了")

        // Update header labels if they've been created
        val centerPanel = rootPane?.contentPane
        if (centerPanel is JComponent) {
            val titleLabel = findComponent(centerPanel, "titleLabel") as? JLabel
            val versionLabel = findComponent(centerPanel, "versionLabel") as? JLabel
            if (titleLabel != null && versionLabel != null) {
                applyHeaderLabels(titleLabel, versionLabel)
            }
        }
    }

    private fun applyHeaderLabels(titleLabel: JLabel, versionLabel: JLabel) {
        val isEn = selectedLanguage == "en"
        titleLabel.text = if (isEn) {
            "\uD83D\uDE80 DeepSeek AI CodeHelper Update"
        } else {
            "\uD83D\uDE80 DeepSeek AI CodeHelper 更新"
        }
        versionLabel.text = if (previousVersion != null) {
            "$previousVersion \u2192 $currentVersion"
        } else {
            if (isEn) "Current version: $currentVersion" else "当前版本: $currentVersion"
        }
    }

    companion object {
        private fun findComponent(parent: Container, name: String): Component? {
            for (c in parent.components) {
                if (name == c.name) return c
                if (c is Container) {
                    findComponent(c, name)?.let { return it }
                }
            }
            return null
        }

        private fun wrapChangelogHtml(rawHtml: String): String {
            val bgColor = if (JBColor.isBright()) "#FFFFFF" else "#2B2B2B"
            val fgColor = if (JBColor.isBright()) "#1A1A1A" else "#E0E0E0"
            val headingColor = if (JBColor.isBright()) "#333333" else "#BBBBBB"
            val codeBg = if (JBColor.isBright()) "#F0F0F0" else "#3A3A3A"

            return """<html>
    <head>
        <style>
            body {
                font-family: sans-serif;
                font-size: 12pt;
                color: $fgColor;
                background-color: $bgColor;
                padding: 8px;
                margin: 0;
            }
            h3 { color: $headingColor; font-size: 14pt; margin: 14px 0 6px 0; font-weight: bold; }
            h4 { color: $headingColor; font-size: 12pt; margin: 10px 0 4px 0; font-weight: bold; }
            ul { margin: 4px 0 8px 0; padding-left: 20px; }
            li { margin: 3px 0; }
            code {
                font-family: monospace;
                font-size: 11pt;
                background-color: $codeBg;
                padding: 1px 4px;
            }
        </style>
    </head>
    <body>$rawHtml</body>
</html>""".trimIndent()
        }
    }
}
