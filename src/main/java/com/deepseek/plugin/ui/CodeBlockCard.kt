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
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
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
        border = JBUI.Borders.customLine(JBColor(0xE0E0E0, 0x3A3A3A), 1)
        background = JBColor(0xF5F5F5, 0x222226)

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
        header.background = JBColor(0xE8E8E8, 0x333337)
        header.border = JBUI.Borders.empty(6, 14, 6, 8)
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

    private fun createCodeArea(): JPanel {
        val codeArea = JBTextArea(code).apply {
            isEditable = false
            isFocusable = false
            lineWrap = false
            font = JBUI.Fonts.create("Monospaced", 13)
            background = JBColor(0xF5F5F5, 0x1A1A1A)
            foreground = JBColor(0x333333, 0xD4D4D4)
            caretColor = foreground
            margin = JBUI.insets(14, 18)
            border = JBUI.Borders.empty()
            selectedTextColor = JBColor.WHITE
            selectionColor = JBColor(0x3399FF, 0x2D5B9E)
        }

        // 用普通面板包裹代码区域，无滚动条，完整展示全部代码行
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0xF5F5F5, 0x1A1A1A)
            add(codeArea, BorderLayout.CENTER)
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
                    segments.addAll(parseNonCodeText(before))
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
                segments.addAll(parseNonCodeText(after))
            }

            // If no segments found at all, treat the whole thing as text
            if (segments.isEmpty() && response.isNotBlank()) {
                segments.addAll(parseNonCodeText(response.trim()))
            }

            return segments
        }

        /**
         * Parse text that may contain pipe tables into [Text] and [Table] segments.
         * A pipe table block:
         *   | H1 | H2 |
         *   |----|----|
         *   | C1 | C2 |
         */
        private fun parseNonCodeText(text: String): List<ResponseSegment> {
            val result = mutableListOf<ResponseSegment>()
            val lines = text.split("\n")
            val tableLineRegex = Regex("^\\s*\\|.*\\|\\s*$")
            var i = 0
            while (i < lines.size) {
                if (lines[i].matches(tableLineRegex)) {
                    // check if the next line is a separator line (|----|)
                    if (i + 1 < lines.size && lines[i + 1].matches(Regex("^\\s*\\|[\\s\\-:|]+\\|\\s*$"))) {
                        // we have a table: header at i, separator at i+1, then data rows
                        val headerLine = lines[i].trim().trim('|').trim()
                        val headers = headerLine.split("|").map { it.trim() }
                        val tableRows = mutableListOf<List<String>>()
                        var j = i + 2
                        while (j < lines.size && lines[j].matches(tableLineRegex)) {
                            val cells = lines[j].trim().trim('|').trim()
                                .split("|").map { it.trim() }
                            // pad or trim to match header count
                            val padded = cells.take(headers.size) +
                                List(maxOf(0, headers.size - cells.size)) { "" }
                            tableRows.add(padded)
                            j++
                        }
                        if (tableRows.isNotEmpty()) {
                            result.add(ResponseSegment.Table(headers, tableRows))
                            i = j
                            continue
                        }
                    }
                }
                // accumulate non-table lines
                val nonTableLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].matches(tableLineRegex)) {
                    nonTableLines.add(lines[i])
                    i++
                }
                // also accumulate when next line is not a valid separator
                while (i < lines.size && lines[i].matches(tableLineRegex)) {
                    if (i + 1 < lines.size && lines[i + 1].matches(Regex("^\\s*\\|[\\s\\-:|]+\\|\\s*$"))) {
                        break // this starts a table, handled above
                    }
                    nonTableLines.add(lines[i])
                    i++
                }
                val content = nonTableLines.joinToString("\n").trim()
                if (content.isNotEmpty()) {
                    result.add(ResponseSegment.Text(content))
                }
            }
            return result
        }
    }
}

/**
 * A segment of a parsed response: plain [Text], a [Code] block, or a [Table].
 */
sealed class ResponseSegment {
    data class Text(val content: String) : ResponseSegment()
    data class Code(val content: String, val language: String = "") : ResponseSegment()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ResponseSegment()
}
