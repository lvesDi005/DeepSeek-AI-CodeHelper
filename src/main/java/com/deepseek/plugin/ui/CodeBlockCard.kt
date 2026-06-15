package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer

/**
 * A modular card that displays a code block with language badge, copy button,
 * and an "insert at cursor" button. Designed for use in chat panels and
 * agent result dialogs.
 *
 * Layout:
 * ┌─────────────────────────────────┐
 * │  JAVA            [📋] [📄]    │  ← Header bar
 * ├─────────────────────────────────┤
 * │  code content ...               │  ← Monospaced code area
 * └─────────────────────────────────┘
 */
class CodeBlockCard(
    private val project: Project?,
    val code: String,
    language: String = "",
    showInsertButton: Boolean = true
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x444444), 1)
        background = JBColor(0xF8F8F8, 0x2B2B2B)

        // ── Header bar ──
        add(createHeader(language, showInsertButton), BorderLayout.NORTH)

        // ── Code area ──
        add(createCodeArea(), BorderLayout.CENTER)
    }

    // ================================================================
    // Header
    // ================================================================

    private fun createHeader(language: String, showInsert: Boolean): JPanel {
        val header = JPanel(BorderLayout())
        header.background = JBColor(0xE8E8E8, 0x3C3C3C)
        header.border = JBUI.Borders.empty(2, 8, 2, 4)
        header.isOpaque = true

        // Language badge (left)
        val langLabel = JLabel(if (language.isNotBlank()) language.uppercase() else "CODE")
        langLabel.font = langLabel.font.deriveFont(Font.BOLD, 11f)
        langLabel.foreground = JBColor(0x666666, 0xAAAAAA)
        header.add(langLabel, BorderLayout.WEST)

        // Buttons (right)
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        actionsPanel.isOpaque = false

        val copyBtn = createActionButton(AllIcons.Actions.Copy, "复制代码") {
            copyToClipboard(code)
        }
        actionsPanel.add(copyBtn)

        if (showInsert) {
            val insertBtn = createActionButton(AllIcons.Actions.Edit, "插入到光标位置") {
                insertCodeAtCursor(project, code)
            }
            actionsPanel.add(Box.createHorizontalStrut(4))
            actionsPanel.add(insertBtn)
        }

        header.add(actionsPanel, BorderLayout.EAST)
        return header
    }

    // ================================================================
    // Code area
    // ================================================================

    private fun createCodeArea(): JBScrollPane {
        val codeArea = JBTextArea(code).apply {
            isEditable = false
            lineWrap = false
            font = JBUI.Fonts.create("Monospaced", 12)
            background = JBColor(0xFCFCFC, 0x1E1E1E)
            foreground = JBColor(0x333333, 0xD4D4D4)
            caretColor = foreground
            margin = JBUI.insets(8)
            border = JBUI.Borders.empty()
            selectedTextColor = JBColor.WHITE
            selectionColor = JBColor(0x3399FF, 0x2D5B9E)
        }

        val maxHeight = 300
        val prefHeight = minOf(codeArea.preferredSize.height + 16, maxHeight)
        return JBScrollPane(codeArea).apply {
            border = JBUI.Borders.empty()
            // No fixed preferred width — let the container control width
            preferredSize = Dimension(100, prefHeight)
            minimumSize = Dimension(100, 40)
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

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

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    private fun flashTooltip(btn: javax.swing.JComponent, msg: String) {
        val orig = btn.toolTipText
        btn.toolTipText = msg
        Timer(1500) { btn.toolTipText = orig }.apply {
            isRepeats = false
            start()
        }
    }

    // ================================================================
    // Static utilities
    // ================================================================

    companion object {
        /**
         * Insert [text] at the current caret position in the active editor.
         * Safe to call from any Swing context.
         */
        @JvmStatic
        fun insertCodeAtCursor(project: Project?, text: String) {
            if (project == null || project.isDisposed) return
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
            WriteCommandAction.runWriteCommandAction(project) {
                val document = editor.document
                val offset = editor.caretModel.offset
                document.insertString(offset, text)
            }
        }

        /**
         * Parse a response string into a list of segments (text blocks and code blocks).
         * Code blocks are delimited by ```language? ... ```.
         */
        @JvmStatic
        fun parseResponse(response: String): List<ResponseSegment> {
            val segments = mutableListOf<ResponseSegment>()
            val regex = Regex("```(\\w*)\\s*\\n?([\\s\\S]*?)```")
            var lastEnd = 0

            for (match in regex.findAll(response)) {
                // text before this code block
                val before = response.substring(lastEnd, match.range.first).trim()
                if (before.isNotEmpty()) {
                    segments.add(ResponseSegment.Text(before))
                }
                val language = match.groupValues[1].ifBlank { "" }
                val code = match.groupValues[2].trim()
                if (code.isNotEmpty()) {
                    segments.add(ResponseSegment.Code(code, language))
                }
                lastEnd = match.range.last + 1
            }

            // remaining text after the last code block
            val after = response.substring(lastEnd).trim()
            if (after.isNotEmpty()) {
                segments.add(ResponseSegment.Text(after))
            }

            // If no code blocks found at all, treat the whole thing as text
            if (segments.isEmpty() && response.isNotBlank()) {
                segments.add(ResponseSegment.Text(response.trim()))
            }

            return segments
        }
    }
}

/**
 * A segment of a parsed response: either plain [Text] or a [Code] block.
 */
sealed class ResponseSegment {
    data class Text(val content: String) : ResponseSegment()
    data class Code(val content: String, val language: String = "") : ResponseSegment()
}
