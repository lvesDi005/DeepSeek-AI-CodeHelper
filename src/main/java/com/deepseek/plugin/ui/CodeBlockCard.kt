package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
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
import javax.swing.JEditorPane
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
    // Code area  — highlight comments to distinguish them from code
    // ================================================================

    private fun createCodeArea(): JPanel {
        val bg = JBColor(0xF5F5F5, 0x1A1A1A)
        val defaultFg = JBColor(0x333333, 0xD4D4D4)
        val commentFg = JBColor(0x999999, 0x6A9955)
        val html = buildHighlightedHtml(code, defaultFg, commentFg)

        val editorPane = JEditorPane("text/html", html).apply {
            isEditable = false
            isFocusable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = JBUI.Fonts.create("Monospaced", 12)
            background = bg
            margin = JBUI.insets(8, 14)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = bg
            add(editorPane, BorderLayout.CENTER)
        }
    }

    // ── Comment-highlighted HTML builder ──

    private fun buildHighlightedHtml(code: String, defaultFg: JBColor, commentFg: JBColor): String {
        val defaultRgb = colorHex(defaultFg)
        val commentRgb = colorHex(commentFg)
        val lines = code.split("\n")
        val sb = StringBuilder()
        var inBlock = false

        for ((i, line) in lines.withIndex()) {
            if (i > 0) sb.append("<br>\n")

            val escaped = escapeHtml(line)

            if (inBlock) {
                // inside a /* */ block comment — find closing */
                val endIdx = escaped.indexOf("*/")
                if (endIdx >= 0) {
                    sb.append("<span style=\"color:$commentRgb\">")
                    sb.append(escaped, 0, endIdx + 2)
                    sb.append("</span>")
                    sb.append(escaped, endIdx + 2, escaped.length)
                    inBlock = false
                } else {
                    sb.append("<span style=\"color:$commentRgb\">").append(escaped).append("</span>")
                }
                continue
            }

            // Find the earliest comment start on this line
            // 1) #  — at line start or preceded only by whitespace
            val hashIdx = findHashComment(escaped)
            // 2) // — line comment
            val slashIdx = escaped.indexOf("//")
            // 3) /* — block comment start
            val blockIdx = escaped.indexOf("/*")

            val candidates = listOfNotNull(
                hashIdx?.let { CommentSite(it, "hash") },
                if (slashIdx >= 0) CommentSite(slashIdx, "slash") else null,
                if (blockIdx >= 0) CommentSite(blockIdx, "block") else null
            )
            val earliest = candidates.minByOrNull { it.index }
            if (earliest == null) {
                sb.append(escaped)
                continue
            }

            // Code before comment
            sb.append(escaped, 0, earliest.index)

            when (earliest.type) {
                "slash" -> {
                    sb.append("<span style=\"color:$commentRgb\">")
                    sb.append(escaped, earliest.index, escaped.length)
                    sb.append("</span>")
                }
                "hash" -> {
                    sb.append("<span style=\"color:$commentRgb\">")
                    sb.append(escaped, earliest.index, escaped.length)
                    sb.append("</span>")
                }
                "block" -> {
                    val rest = escaped.substring(earliest.index)
                    val endIdx = rest.indexOf("*/")
                    if (endIdx >= 0) {
                        sb.append("<span style=\"color:$commentRgb\">")
                        sb.append(rest, 0, endIdx + 2)
                        sb.append("</span>")
                        sb.append(rest, endIdx + 2, rest.length)
                    } else {
                        sb.append("<span style=\"color:$commentRgb\">")
                        sb.append(rest)
                        sb.append("</span>")
                        inBlock = true
                    }
                }
            }
        }
        return """<html><body style="white-space:pre-wrap;font-family:'Monospaced',monospace;font-size:12px;line-height:1.35;color:$defaultRgb;margin:0;padding:0">$sb</body></html>"""
    }

    private fun findHashComment(escaped: String): Int? {
        // # at start of line
        if (escaped.startsWith("#")) return 0
        // # preceded only by whitespace
        val trimmed = escaped.trimStart()
        return if (trimmed.startsWith("#")) escaped.length - trimmed.length
        else null
    }

    private data class CommentSite(val index: Int, val type: String)

    private fun escapeHtml(s: String): String {
        return s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun colorHex(c: JBColor): String {
        val rgb = c.rgb and 0xFFFFFF
        return "#%06X".format(rgb)
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun createActionButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JPanel {
        val btn = createToolbarButton(icon, tooltip, onClick = onClick)
        val wrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        wrapper.isOpaque = false
        wrapper.add(btn)
        return wrapper
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
