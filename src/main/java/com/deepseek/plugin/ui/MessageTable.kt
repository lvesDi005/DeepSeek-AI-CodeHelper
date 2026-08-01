package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * 完整一体化暗色表格组件。
 *
 * 特性：
 * - paintComponent 统一绘制全部横竖网格线，无双重边框、无断裂
 * - GridBagLayout 全局布局，每列宽度严格对齐
 * - 表头加粗高亮，数据行自动换行
 * - 单元格内可嵌彩色标识（绿色 ✓ / 红色 ✕），居中摆放
 * - 长文本自动换行，不溢出、不截断
 * - 统一内边距，垂直居中
 */
class MessageTable(
    headers: List<String>,
    rows: List<List<String>>
) : JPanel(GridBagLayout()) {

    private val gridColor = PluginTheme.border()
    private val headerBg = PluginTheme.color(0xE8E8E8, 0x2B2B2B)
    private val cellBg = PluginTheme.background()
    private val headerFg = PluginTheme.textHeading()
    private val cellFg = PluginTheme.textPrimary()
    private val cellPadding = EmptyBorder(7, 10, 7, 10)

    private val rowCount: Int
    private val colCount: Int

    init {
        isOpaque = false
        colCount = headers.size
        rowCount = rows.size

        // ── 用 GridBagLayout 构建全部单元格 ──
        // 表头行
        for (col in 0 until colCount) {
            val headerText = headers.getOrElse(col) { "" }
            val cell = createCell(headerText, isHeader = true, row = 0, col = col)
            val gbc = cellConstraints(row = 0, col = col)
            add(cell, gbc)
        }

        // 数据行
        for (row in 0 until rowCount) {
            val rowData = rows.getOrElse(row) { emptyList() }
            for (col in 0 until colCount) {
                val cellText = rowData.getOrElse(col) { "" }
                val cell = createCell(cellText, isHeader = false, row = row + 1, col = col)
                val gbc = cellConstraints(row = row + 1, col = col)
                add(cell, gbc)
            }
        }
    }

    private fun cellConstraints(row: Int, col: Int): GridBagConstraints {
        return GridBagConstraints().apply {
            gridx = col
            gridy = row
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.BOTH
            // 无 insets — 网格线由 paintComponent 统一绘制
            insets = java.awt.Insets(0, 0, 0, 0)
        }
    }

    private fun createCell(text: String, isHeader: Boolean, row: Int, col: Int): JPanel {
        val isMarker = text.matches(Regex("\\s*[✓✔✅✕×❌]\\s*"))

        val contentComponent: Component = if (isHeader || isMarker) {
            // 表头或纯标识单元格 → 居中 JLabel
            val label = JLabel(formatCellText(text), SwingConstants.CENTER).apply {
                font = this.font.deriveFont(if (isHeader) Font.BOLD else Font.PLAIN, 12f)
                foreground = if (isHeader) headerFg else cellFg
            }
            label
        } else {
            // 数据单元格 → 自动换行 JBTextArea
            val ta = JBTextArea(text).apply {
                isEditable = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = true
                font = this.font.deriveFont(Font.PLAIN, 12f)
                foreground = cellFg
                background = Color(0, 0, 0, 0)
                isOpaque = false
                border = EmptyBorder(0, 0, 0, 0)
                margin = java.awt.Insets(0, 0, 0, 0)
            }
            ta
        }

        return object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isHeader) headerBg else cellBg
                g2.fillRect(0, 0, width, height)

                // 如果是标识单元格，绘制彩色标记
                if (isMarker && !isHeader) {
                    drawMarker(g2, text)
                }
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = cellPadding
            add(contentComponent, BorderLayout.CENTER)
            // 存储行列位置供 paintComponent 网格线使用
            putClientProperty("row", row)
            putClientProperty("col", col)
        }
    }

    /** 在单元格内居中绘制彩色 ✓ / ✕ */
    private fun drawMarker(g2: Graphics2D, text: String) {
        val marker = text.trim()
        val isGreen = marker in setOf("✓", "✔", "✅")
        val isRed = marker in setOf("✕", "×", "❌")
        if (!isGreen && !isRed) return

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = g2.font.deriveFont(Font.BOLD, 16f)
        val fm = g2.fontMetrics
        val display = if (isGreen) "✓" else "✕"
        val x = (width - fm.stringWidth(display)) / 2
        val y = (height + fm.ascent) / 2 - 2
        g2.color = if (isGreen) Color(0x43A047) else Color(0xE53935)
        g2.drawString(display, x, y)
    }

    /** 格式化表头/简单单元格文字：HTML 包裹彩色标记 */
    private fun formatCellText(text: String): String {
        var r = text
            .replace("✕", "<font color='#E53935'>✕</font>")
            .replace("×", "<font color='#E53935'>×</font>")
            .replace("✓", "<font color='#43A047'>✓</font>")
            .replace("✔", "<font color='#43A047'>✔</font>")
            .replace("✅", "<font color='#43A047'>✅</font>")
            .replace("❌", "<font color='#E53935'>❌</font>")
        if (r != text) {
            r = "<html><div style='text-align:center'>$r</div></html>"
        }
        return r
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.dispose()
        super.paintComponent(g)
    }

    override fun paintChildren(g: Graphics) {
        super.paintChildren(g)
        // 在子组件绘制之后，绘制网格线（覆盖在子组件之上）
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = gridColor

        // 收集所有子组件的位置
        val cells = mutableMapOf<Pair<Int, Int>, Component>() // (row, col) -> component
        var maxRow = 0
        var maxCol = 0
        for (comp in components) {
            val jc = comp as? javax.swing.JComponent ?: continue
            val r = jc.getClientProperty("row") as? Int ?: continue
            val c = jc.getClientProperty("col") as? Int ?: continue
            cells[Pair(r, c)] = comp
            maxRow = maxOf(maxRow, r)
            maxCol = maxOf(maxCol, c)
        }
        if (cells.isEmpty()) {
            g2.dispose()
            return
        }

        // 按行收集，绘制水平线
        for (row in 0..maxRow) {
            // 找到这一行最左边的 cell 和最右边的 cell
            var leftX = Int.MAX_VALUE
            var rightX = 0
            var hasCells = false
            for (col in 0..maxCol) {
                val comp = cells[Pair(row, col)] ?: continue
                hasCells = true
                leftX = minOf(leftX, comp.x)
                rightX = maxOf(rightX, comp.x + comp.width)
            }
            if (!hasCells) continue
            // 在行顶部画水平线（第一行顶部不画，因为表格本身可能有圆角边框）
            if (row > 0) {
                g2.drawLine(leftX, cells[Pair(row, 0)]!!.y, rightX, cells[Pair(row, 0)]!!.y)
            }
            // 在行底部画水平线
            // 使用该行任一组件
            val anyComp = cells.entries.first { it.key.first == row }.value
            g2.drawLine(leftX, anyComp.y + anyComp.height, rightX, anyComp.y + anyComp.height)
        }

        // 按列收集，绘制垂直线
        for (col in 0..maxCol) {
            var topY = Int.MAX_VALUE
            var bottomY = 0
            var hasCells = false
            for (row in 0..maxRow) {
                val comp = cells[Pair(row, col)] ?: continue
                hasCells = true
                topY = minOf(topY, comp.y)
                bottomY = maxOf(bottomY, comp.y + comp.height)
            }
            if (!hasCells) continue
            // 列左侧（第一列左侧不画）
            if (col > 0) {
                g2.drawLine(cells[Pair(0, col)]!!.x, topY, cells[Pair(0, col)]!!.x, bottomY)
            }
            // 列右侧
            val anyComp = cells.entries.first { it.key.second == col }.value
            g2.drawLine(anyComp.x + anyComp.width, topY, anyComp.x + anyComp.width, bottomY)
        }

        g2.dispose()
    }
}
