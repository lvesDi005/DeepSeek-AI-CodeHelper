package com.deepseek.plugin.ui

import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.UIManager

/**
 * 跑马灯标签 — 文本从右向左自动循环滚动。
 *
 * - 仅在文本宽度超过组件宽度时启动滚动
 * - 鼠标悬停暂停，移出继续
 * - 组件从容器移除时自动停止 Timer
 */
class MarqueeLabel(
    /** 显示的文本 */
    private var displayText: String
) : JComponent() {

    /** 当前绘制偏移量（像素），负值向左移 */
    private var scrollOffset = 0f

    /** 驱动滚动的 Swing Timer */
    private var scrollTimer: Timer? = null

    /** 鼠标是否悬停 */
    private var hovered = false

    /** 文本像素宽度（缓存，避免每帧都算） */
    private var textPxWidth = 0

    /** 是否需要滚动 */
    private val needsScroll: Boolean
        get() {
            updateTextWidth()
            return textPxWidth > width && width > 0
        }

    init {
        isOpaque = false
        // JComponent 没有默认字体，显式设置以便 deriveFont() 可用
        font = UIManager.getFont("Label.font") ?: Font("Dialog", Font.PLAIN, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                scrollTimer?.stop()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                if (needsScroll) ensureTimerRunning()
            }
        })
    }

    // ── 文本宽度缓存 ──

    private fun updateTextWidth() {
        val fm: FontMetrics = getFontMetrics(font)
        textPxWidth = fm.stringWidth(displayText)
    }

    // ── Timer 管理 ──

    private fun ensureTimerRunning() {
        if (!needsScroll) return
        if (scrollTimer != null && scrollTimer!!.isRunning) return
        scrollOffset = width.toFloat()
        scrollTimer = Timer(30) {
            scrollOffset -= 1.5f
            // 文本完全移出左边界 → 从右侧重新入
            if (scrollOffset < -textPxWidth) {
                scrollOffset = width.toFloat()
            }
            repaint()
        }.apply { start() }
    }

    private fun stopTimer() {
        scrollTimer?.stop()
        scrollTimer = null
    }

    // ── 生命周期 ──

    override fun addNotify() {
        super.addNotify()
        if (needsScroll) ensureTimerRunning()
    }

    override fun removeNotify() {
        stopTimer()
        super.removeNotify()
    }

    // ── 尺寸提示：让布局管理器按文本宽度给空间 ──

    override fun getPreferredSize(): Dimension {
        updateTextWidth()
        val fm: FontMetrics = getFontMetrics(font)
        return Dimension(textPxWidth + 4, fm.height + 4)
    }

    override fun getMinimumSize(): Dimension {
        val fm: FontMetrics = getFontMetrics(font)
        return Dimension(20, fm.height + 4)
    }

    // ── 绘制 ──

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        if (displayText.isEmpty()) return

        // 首次绘制或宽度变化后启动 Timer
        if (!hovered && needsScroll && (scrollTimer == null || !scrollTimer!!.isRunning)) {
            ensureTimerRunning()
        }

        val g2d = g.create() as Graphics2D
        try {
            g2d.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            )
            g2d.font = font
            g2d.color = foreground

            val fm: FontMetrics = g2d.fontMetrics
            val textH = fm.ascent
            // 垂直居中
            val y = (height - fm.height) / 2f + textH

            if (!needsScroll) {
                // 文本不长 → 静态居中绘制
                g2d.drawString(displayText, 4f, y)
            } else {
                // 绘制主文本
                g2d.drawString(displayText, scrollOffset, y)
                // 如果尾部有空白 → 补绘一个副本实现无缝循环
                if (scrollOffset + textPxWidth < width) {
                    g2d.drawString(displayText, scrollOffset + textPxWidth + width, y)
                }
            }
        } finally {
            g2d.dispose()
        }
    }

    // ── 外部接口 ──

    /** 更新文本并重启滚动（若需要） */
    fun setText(newText: String) {
        displayText = newText
        textPxWidth = 0  // 强制重算
        stopTimer()
        if (needsScroll) ensureTimerRunning()
        repaint()
    }
}
