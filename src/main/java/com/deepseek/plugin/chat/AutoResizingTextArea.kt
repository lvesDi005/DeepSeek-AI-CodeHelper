package com.deepseek.plugin.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultHighlighter
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.max

/**
 * 输入区域组件。具有自动伸缩高度、@ 文件引用弹窗、Enter 发送等功能。
 *
 * 设计目标：
 * - 独立于 ChatPanel 的可复用组件
 * - 通过 [onSend] 回调解耦发送逻辑
 * - 通过 [isStreaming] lambda 从外部获取流式状态
 */
class AutoResizingTextArea(
    rows: Int,
    cols: Int,
    private val project: Project,
    private val onSend: () -> Unit,
    private val isStreaming: () -> Boolean
) : JBTextArea(rows, cols) {

    private var minHeight: Int = 0
    private var maxHeight: Int = 0
    private val placeholderText = "欢迎使用, 请输入内容按Enter发送消息, Shift+Enter换行"
    private val refHighlightPainter = DefaultHighlighter.DefaultHighlightPainter(
        JBColor(Color(0xFFF176), Color(0x8D6E00))
    )
    private val refPattern = Regex("@[\\w.\\-/]+")
    private var suppressingPopup = false

    init {
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create("Monospaced", 13)
        margin = JBUI.insets(8, 10)

        // Enter to send, Shift+Enter to insert newline
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown && !e.isControlDown && !e.isAltDown && !e.isMetaDown && !isStreaming()) {
                    e.consume()
                    onSend()
                }
            }
        })

        // Auto-resize + @-highlight + @-trigger on content changes
        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                adjustHeight()
                refreshHighlights()
                if (!suppressingPopup) checkAtTrigger()
            }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                adjustHeight()
                refreshHighlights()
            }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                adjustHeight()
                refreshHighlights()
            }
        })

        // Recalculate when the component is resized (e.g. panel splitter moved)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { adjustHeight() }
        })
    }

    /** Show file-reference popup when "@" is typed at word start. */
    private fun checkAtTrigger() {
        val text = text
        val pos = caretPosition
        if (pos <= 0) return
        if (pos > text.length) return
        val charBefore = text[pos - 1]
        if (charBefore != '@') return
        // Check it's at word start (preceded by space or start of line)
        if (pos >= 2 && text[pos - 2].isLetterOrDigit()) return
        showFileRefPopup(pos)
    }

    private fun showFileRefPopup(atPos: Int) {
        // Build file list from project root — exclude hidden files/dirs and common build dirs
        val projectDir = project.basePath ?: return
        val baseFile = java.io.File(projectDir)
        val excludeDirs = setOf("build", ".gradle", "gradle", "node_modules", "target", "dist", "out", "bin", "obj")
        val entries = baseFile.listFiles()
            ?.filter { f ->
                !f.name.startsWith(".") &&  // skip hidden files/dirs
                f.name !in excludeDirs &&    // skip common build dirs
                (f.isFile || f.isDirectory)
            }
            ?.sortedBy { it.name.lowercase() } ?: return

        val listModel = DefaultListModel<String>()
        val allEntries = entries.map { it.name + if (it.isDirectory) "/" else "" }
        allEntries.forEach { listModel.addElement(it) }

        val list = JList(listModel).apply {
            font = font.deriveFont(12f)
            foreground = JBColor(Color(0x333333), Color(0xCCCCCC))
            background = JBColor(Color(0xFFFFFF), Color(0x2B2B2B))
            selectionBackground = JBColor(Color(0xE3F2FD), Color(0x264F78))
            fixedCellHeight = 22
        }

        val popup = JPopupMenu().apply {
            isOpaque = true
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(Color(0xC0C0C0), Color(0x555555)), 1),
                JBUI.Borders.empty(2)
            )
            background = list.background
            layout = BorderLayout()
            val scrollPane = JBScrollPane(list).apply {
                preferredSize = Dimension(260, minOf(entries.size, 10) * 22 + 4)
                border = JBUI.Borders.empty()
                isOpaque = false
            }
            add(scrollPane, BorderLayout.CENTER)
        }

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    val selected = list.selectedValue
                    if (selected != null) insertRef(selected, atPos, popup)
                }
            }
        })

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    val selected = list.selectedValue
                    if (selected != null) insertRef(selected, atPos, popup)
                } else if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    popup.isVisible = false
                }
            }
        })

        // Position popup ABOVE the @ character
        try {
            val caretRect2d = modelToView2D(atPos)
            val popupH = minOf(entries.size, 10) * 22 + 4 + 8 // list + border + padding
            val pt = java.awt.Point(
                maxOf(0, caretRect2d.x.toInt() - 4),
                caretRect2d.y.toInt() - popupH - 4
            )
            SwingUtilities.convertPointToScreen(pt, this)
            popup.setLocation(pt)
            popup.isVisible = true
            list.requestFocusInWindow()
        } catch (_: Exception) { /* bounds not ready */ }
    }

    private fun insertRef(entry: String, atPos: Int, popup: JPopupMenu) {
        popup.isVisible = false
        suppressingPopup = true
        val text = text
        val before = text.substring(0, atPos - 1) // remove '@'
        val after = text.substring(atPos)
        this.text = "$before@$entry $after"
        caretPosition = atPos - 1 + entry.length + 2 // after inserted ref + space
        suppressingPopup = false
        requestFocusInWindow()
        refreshHighlights()
    }

    /** Highlight all @references in the text with yellow painter. */
    private fun refreshHighlights() {
        highlighter.removeAllHighlights()
        for (match in refPattern.findAll(text)) {
            try {
                highlighter.addHighlight(match.range.first, match.range.last + 1, refHighlightPainter)
            } catch (_: Exception) { /* ignore invalid ranges */ }
        }
    }

    override fun addNotify() {
        super.addNotify()
        val fm = getFontMetrics(font)
        val lineH = fm.height
        minHeight = lineH * 4 + 10
        maxHeight = lineH * 12 + 10
        // Defer first height adjustment until layout is complete
        SwingUtilities.invokeLater { adjustHeight() }
    }

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
        // Draw placeholder when empty and not focused
        if (text.isEmpty() && !hasFocus()) {
            val g2 = g.create() as java.awt.Graphics
            try {
                g2.color = JBColor(Color(0xAAAAAA), Color(0x666666))
                g2.font = font.deriveFont(Font.PLAIN, 12f)
                val fm = g2.fontMetrics
                val x = insets.left + 2
                val y = insets.top + fm.ascent + 2
                g2.drawString(placeholderText, x, y)
            } finally {
                g2.dispose()
            }
        }
    }

    private fun adjustHeight() {
        if (minHeight <= 0 || maxHeight <= 0) return
        val fm = getFontMetrics(font)
        val lineH = fm.height
        // Wait until the component has a meaningful width
        if (width <= 0) return
        // Approximate line count from text wrapping
        val lines = if (text.isEmpty()) 1 else {
            val avail = maxOf(1, width - 8)
            text.split('\n').sumOf { word ->
                maxOf(1, ceil(fm.stringWidth(word).toDouble() / avail).toInt())
            }
        }
        val h = (lineH * lines + 10).coerceIn(minHeight, maxHeight)
        if (h != height) {
            preferredSize = Dimension(preferredSize.width, h)
            revalidate()
        }
    }
}
