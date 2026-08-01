package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
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
        border = JBUI.Borders.customLine(PluginTheme.border(), 1)
        background = PluginTheme.color(0xF5F5F5, 0x2B2B2B)

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
        header.background = PluginTheme.color(0xE8E8E8, 0x303030)
        header.border = JBUI.Borders.empty(4, 10, 4, 6)
        header.isOpaque = true

        // Language badge (left)
        val langLabel = JLabel(if (language.isNotBlank()) language.uppercase() else "CODE")
        langLabel.font = langLabel.font.deriveFont(Font.BOLD, 11f)
        langLabel.foreground = PluginTheme.textHeading()
        header.add(langLabel, BorderLayout.WEST)

        // Buttons (right)
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
        actionsPanel.isOpaque = false

        val copyBtn = createActionButton(AllIcons.Actions.Copy, I18n.tr("code.copy"), "code.copy") {
            copyToClipboard(code)
        }
        actionsPanel.add(copyBtn)

        if (showInsert) {
            val insertBtn = createActionButton(AllIcons.Actions.Edit, I18n.tr("code.insert"), "code.insert") {
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
        val bg = PluginTheme.color(0xF5F5F5, 0x2B2B2B)
        val fg = PluginTheme.textPrimary()

        // 覆写 viewport 尺寸：JTextArea 默认返回固定几行高，这里改为全部内容高度，
        // 保证长代码完整展开（外层消息面板负责整体纵向滚动）。
        val textArea = object : JBTextArea(code) {
            override fun getPreferredScrollableViewportSize(): Dimension {
                val fm = getFontMetrics(font)
                val lineCount = code.lines().size.coerceAtLeast(1)
                val textH = lineCount * fm.height
                val h = textH + insets.top + insets.bottom + margin.top + margin.bottom
                return Dimension(100, h)
            }
        }.apply {
            isEditable = false
            isFocusable = true
            background = bg
            foreground = fg
            font = JBUI.Fonts.create("Monospaced", JBUI.Fonts.label().size)
            // 不换行：保持代码原始格式（缩进/对齐），超长行通过横向滚动条左右拉动查看
            lineWrap = false
            tabSize = 4
            margin = JBUI.insets(4, 10)
            border = JBUI.Borders.empty()
            caretColor = fg
            // Allow text selection for copy (isEditable=false still allows selection in JBTextArea)
        }

        // 用滚动面板包裹：横向可左右拉动，竖向恰好全显
        // 三环锁定高度（preferred/minimum/maximum）= 全部内容高度，防止 BoxLayout 压缩
        val scrollPane = JBScrollPane(textArea).apply {
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
            isOpaque = true
            background = bg
            viewport.isOpaque = true
            viewport.background = bg
            val contentHeight = textArea.preferredSize.height + JBUI.scale(2)
            preferredSize = Dimension(100, contentHeight)
            minimumSize = Dimension(100, contentHeight)
            maximumSize = Dimension(Int.MAX_VALUE, contentHeight)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = bg
            add(scrollPane, BorderLayout.CENTER)
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
        return """<html><body style="white-space:pre-wrap;word-break:break-all;font-family:'Monospaced',monospace;font-size:${JBUI.Fonts.label().size}px;line-height:1.25;color:$defaultRgb;margin:0;padding:0">$sb</body></html>"""
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

    private fun createActionButton(icon: javax.swing.Icon, tooltip: String, tooltipKey: String? = null, onClick: () -> Unit): JPanel {
        val btn = createToolbarButton(icon, tooltip, tooltipKey = tooltipKey, onClick = onClick)
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
         *
         * 逐行流式扫描，支持两种 Markdown 围栏：``` 和 ~~~。
         * - 围栏行：``` 或 ~~~，可选语言标签（须后随换行才算标签）
         * - 未闭合围栏：宽容处理，从围栏行到结尾视为代码块
         * - 有语言标签 → 代码块；无语言标签 → 用 [looksLikeCode] 判断，
         *   不像代码的内容按普通文字处理（避免文字被塞进代码框）
         */
        @JvmStatic
        fun parseResponse(response: String): List<ResponseSegment> {
            val segments = mutableListOf<ResponseSegment>()
            val textBuffer = StringBuilder()

            fun flushText() {
                val text = textBuffer.toString().trim()
                textBuffer.clear()
                if (text.isNotEmpty()) {
                    segments.addAll(parseNonCodeText(text))
                }
            }

            val lines = response.split("\n")
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val fenceMatch = FENCE_REGEX.find(line)
                if (fenceMatch != null) {
                    flushText()
                    val fence = fenceMatch.groupValues[1]
                    val language = fenceMatch.groupValues[2]
                    val codeLines = mutableListOf<String>()
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        if (lines[j].trim() == fence) {
                            closed = true
                            break
                        }
                        codeLines.add(lines[j])
                        j++
                    }
                    val code = codeLines.joinToString("\n").trim()
                    if (code.isNotEmpty()) {
                        // 有语言标签或未闭合围栏（围栏意图明确）→ 代码块；
                        // 已闭合且无语言标签 → 用 looksLikeCode 判断，不像代码按文字处理
                        if (language.isNotBlank() || !closed || looksLikeCode(code)) {
                            segments.add(ResponseSegment.Code(code, language))
                        } else {
                            // 无语言标签且不像代码：按普通文字处理（表格/说明文本等）
                            segments.addAll(parseNonCodeText(code))
                        }
                    }
                    i = if (closed) j + 1 else lines.size
                } else {
                    textBuffer.appendLine(line)
                    i++
                }
            }
            flushText()

            // If no segments found at all, treat the whole thing as text
            if (segments.isEmpty() && response.isNotBlank()) {
                segments.addAll(parseNonCodeText(response.trim()))
            }

            return segments
        }

        /** 围栏行：``` 或 ~~~，可选语言标签（word/点/加减号），标签后必须到行尾 */
        private val FENCE_REGEX = Regex("^\\s*(```|~~~)\\s*([\\w.+\\-]*)\\s*$")

        /**
         * 判断围栏内容是否像代码或流程描述。无语言标签时用于分类：
         * 1. 代码特征（关键字/分号/花括号/调用）≥2 且非大段中文 → 代码
         * 2. 流程特征（箭头符号/图语法/步骤词）→ 也进代码框（伪代码、时序图、流程图等）
         */
        private fun looksLikeCode(code: String): Boolean {
            val trimmed = code.trim()
            if (trimmed.isEmpty()) return false
            val lines = trimmed.lines()

            val strongKeywords = listOf(
                "class ", "interface ", "enum ", "struct ", "func ", "function ", "def ",
                "import ", "package ", "namespace ", "using ", "#include", "public ", "private ",
                "protected ", "static ", "return ", "const ", "=>", "::", "=== "
            )
            val hasStrong = strongKeywords.any { trimmed.contains(it) }
            val hasSemicolons = lines.count { it.trimEnd().endsWith(";") } >= 1
            val hasBraces = trimmed.contains("{") && trimmed.contains("}")
            val hasCall = Regex("\\w+\\([^)]*\\)").containsMatchIn(trimmed)

            // 中文占比（代码通常不含大段中文）
            val chineseCount = trimmed.count { it in '\u4e00'..'\u9fff' }
            val ratio = chineseCount.toDouble() / trimmed.length.coerceAtLeast(1)

            val score = listOf(hasStrong, hasSemicolons, hasBraces, hasCall).count { it }
            val isCode = score >= 2 && ratio < 0.3

            // ── 流程特征：伪代码 / 时序图 / 流程图 / 步骤序列 → 也进代码框 ──
            val arrowLines = lines.count { line ->
                line.contains("->") || line.contains("->>") || line.contains("=>") ||
                    line.contains("→") || line.contains("←") || line.contains("-->") ||
                    line.contains("==>")
            }
            val flowKeywords = listOf(
                "flowchart", "graph TD", "sequenceDiagram", "stateDiagram", "classDiagram",
                "participant", "erDiagram", "步骤", "Step ", "第\\d+步"
            )
            val hasFlowKeyword = flowKeywords.any { kw ->
                if (kw.startsWith("第")) Regex("第\\s*\\d+\\s*步").containsMatchIn(trimmed)
                else trimmed.contains(kw)
            }
            // 伪代码流程控制关键字：强关键字单独出现即判定；弱关键字需 ≥2 个（防英文句子误判）
            val strongPseudo = listOf(
                "while ", "loop", "repeat ", "switch ", "end if", "endfor", "endwhile",
                "for each", "begin ", "until ", "then "
            )
            val weakPseudo = listOf("if ", "else ", "case ", "do ", "try ", "catch ")
            val hasStrongPseudo = strongPseudo.any { trimmed.contains(it, ignoreCase = true) }
            val weakCount = weakPseudo.count { trimmed.contains(it, ignoreCase = true) }
            val hasPseudo = hasStrongPseudo || weakCount >= 2
            // 2 行以上含箭头，或含图语法/步骤关键词，或伪代码结构 → 视为流程
            val isFlow = (arrowLines >= 1 && lines.size >= 2) || hasFlowKeyword || hasPseudo

            return isCode || isFlow
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
