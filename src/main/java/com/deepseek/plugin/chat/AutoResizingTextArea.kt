package com.deepseek.plugin.chat

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import kotlin.math.ceil

/**
 * 现代化聊天输入框。
 *
 * 设计要点：
 * - 非等宽字体（聊天输入框用等宽字体会显得老气）
 * - 自伸缩高度（4~12 行）
 * - @ 文件引用弹窗
 * - Enter 发送，Shift+Enter 换行
 * - 透明背景 — 由外层 ChatInputBar 的圆角容器负责绘制
 */
class AutoResizingTextArea(
    rows: Int,
    cols: Int,
    private val project: Project,
    private val onSend: () -> Unit,
    private val isStreaming: () -> Boolean
) : javax.swing.JTextArea(rows, cols) {

    private var minHeight: Int = 0
    private var maxHeight: Int = 0
    private val placeholderText = "输入消息...   @引用文件  ·  Enter发送  ·  Shift+Enter换行"
    private val refPattern = Regex("@[\\w.\\-/]+")
    private var suppressingPopup = false

    init {
        isOpaque = false
        isFocusable = true
        lineWrap = true
        wrapStyleWord = true
        font = font.deriveFont(Font.PLAIN, 13f)
        margin = JBUI.insets(10, 14)
        border = EmptyBorder(0, 0, 0, 0)
        foreground = JBColor(0x1A1A1A, 0xE0E0E0)
        caretColor = foreground
        selectionColor = JBColor(0x3399FF, 0x536DFE)
        selectedTextColor = JBColor.WHITE

        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown && !e.isControlDown && !e.isAltDown && !e.isMetaDown && !isStreaming()) {
                    e.consume()
                    onSend()
                }
            }
        })

        document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                adjustHeight()
                if (!suppressingPopup) checkAtTrigger()
            }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                adjustHeight()
            }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                adjustHeight()
            }
        })

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { adjustHeight() }
        })
    }

    private fun checkAtTrigger() {
        val text = text
        val pos = caretPosition
        if (pos <= 0) return
        if (pos > text.length) return
        val charBefore = text[pos - 1]
        if (charBefore != '@') return
        if (pos >= 2 && text[pos - 2].isLetterOrDigit()) return
        showFileRefPopup(pos)
    }

    private fun showFileRefPopup(atPos: Int) {
        val projectDir = project.basePath ?: return
        val baseFile = java.io.File(projectDir)
        val excludeDirs = setOf("build", ".gradle", "gradle", "node_modules", "target", "dist", "out", "bin", "obj")
        val entries = baseFile.listFiles()
            ?.filter { f ->
                !f.name.startsWith(".") &&
                f.name !in excludeDirs &&
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
            val scrollPane = javax.swing.JScrollPane(list).apply {
                preferredSize = Dimension(260, minOf(entries.size, 10) * 22 + 4)
                border = JBUI.Borders.empty()
                isOpaque = false
            }
            add(scrollPane)
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

        try {
            val caretRect2d = modelToView2D(atPos)
            val popupH = minOf(entries.size, 10) * 22 + 4 + 8
            val pt = java.awt.Point(
                maxOf(0, caretRect2d.x.toInt() - 4),
                caretRect2d.y.toInt() - popupH - 4
            )
            SwingUtilities.convertPointToScreen(pt, this)
            popup.setLocation(pt)
            popup.isVisible = true
            list.requestFocusInWindow()
        } catch (_: Exception) { }
    }

    private fun insertRef(entry: String, atPos: Int, popup: JPopupMenu) {
        popup.isVisible = false
        suppressingPopup = true
        val text = text
        val before = text.substring(0, atPos - 1)
        val after = text.substring(atPos)
        this.text = "$before@$entry $after"
        caretPosition = atPos - 1 + entry.length + 2
        suppressingPopup = false
        requestFocusInWindow()
    }

    override fun addNotify() {
        super.addNotify()
        val fm = getFontMetrics(font)
        val lineH = fm.height
        minHeight = lineH * 4 + 10
        maxHeight = lineH * 12 + 10
        SwingUtilities.invokeLater { adjustHeight() }
    }

    override fun getScrollableTracksViewportHeight(): Boolean = false

    override fun paintComponent(g: Graphics) {
        if (text.isEmpty() && !hasFocus()) {
            val g2 = g.create() as Graphics2D
            try {
                val fm = g2.fontMetrics
                g2.color = JBColor(Color(0xAAAAAA), Color(0x666666))
                g2.font = font.deriveFont(Font.PLAIN, 12f)
                val x = margin.left + 2
                val y = margin.top + fm.ascent
                g2.drawString(placeholderText, x, y)
            } finally {
                g2.dispose()
            }
        }
        super.paintComponent(g)
    }

    private fun adjustHeight() {
        if (minHeight <= 0 || maxHeight <= 0) return
        val fm = getFontMetrics(font)
        val lineH = fm.height
        if (width <= 0) return
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
