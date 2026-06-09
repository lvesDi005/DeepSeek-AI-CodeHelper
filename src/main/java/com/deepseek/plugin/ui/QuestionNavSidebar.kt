package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * A narrow dot‑only sidebar on the left edge of the chat panel.
 *
 * Each dot represents one user question.
 *
 * - **Hover** → standard Swing tooltip shows the full question text
 *                  (unclipped because ToolTipManager paints globally).
 * - **Click** → scrolls to the corresponding **AI response**.
 *
 * Visibility is driven by the container width (≥ [widthThreshold]).
 */
class QuestionNavSidebar : JPanel(null) {

    // ── Data ──

    data class NodeData(
        val component: JComponent,  // the AI response panel (or user panel as fallback)
        val text: String            // full question text (for tooltip)
    )

    private var nodes: List<NodeData> = emptyList()
    private var visibleStartIndex = 0

    // ── Visual constants ──

    companion object {
        const val SIDEBAR_WIDTH = 36    // narrow — just dots
        private const val DOT_RADIUS = 5
        const val MAX_VISIBLE_NODES = 6
        private val LINE_COLOR = JBColor(0xCCCCCC, 0x555555)
        private val DOT_COLOR = JBColor(0x1A73E8, 0x64B5F6)
        private val DOT_HOVER_COLOR = JBColor(0x1557B0, 0x82C3FD)
        private val BG_COLOR = JBColor(0xF8F9FA, 0x2B2B2D)
        private const val NODE_HEIGHT = 26
        /** Width threshold (of the parent container) for showing. */
        var widthThreshold: Int = 500
    }

    init {
        preferredSize = Dimension(SIDEBAR_WIDTH, 0)
        minimumSize = Dimension(SIDEBAR_WIDTH, 0)
        background = BG_COLOR
    }

    // ══════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════

    fun setNodes(newNodes: List<NodeData>) {
        nodes = newNodes
        visibleStartIndex = 0
        rebuildVisibleNodes()
        revalidate()
        repaint()
    }

    /**
     * Set which slice of the full node list is currently visible in the sidebar.
     * The sidebar renders at most [MAX_VISIBLE_NODES] dots centered around [startIndex].
     */
    fun setVisibleRange(startIndex: Int) {
        if (nodes.isEmpty()) return
        val total = nodes.size
        if (total <= MAX_VISIBLE_NODES) {
            if (visibleStartIndex != 0) {
                visibleStartIndex = 0
                rebuildVisibleNodes()
                revalidate()
                repaint()
            }
            return
        }
        val newStart = startIndex.coerceIn(0, total - MAX_VISIBLE_NODES)
        if (newStart != visibleStartIndex) {
            visibleStartIndex = newStart
            rebuildVisibleNodes()
            revalidate()
            repaint()
        }
    }

    fun clear() {
        nodes = emptyList()
        visibleStartIndex = 0
        removeAll()
        revalidate()
        repaint()
    }

    fun shouldShow(parentWidth: Int): Boolean {
        return nodes.size >= 1 && parentWidth >= widthThreshold
    }

    // ══════════════════════════════════════════════════════════════════
    //  Layout — 弹性均匀分布
    // ══════════════════════════════════════════════════════════════════

    override fun doLayout() {
        val w = width
        val h = height
        val n = componentCount
        if (n == 0) return

        val usableH = h.coerceAtLeast(NODE_HEIGHT * n)
        val spacing = usableH.toDouble() / (n + 1)

        for (i in 0 until n) {
            val node = getComponent(i)
            val centerY = (spacing * (i + 1)).toInt()
            node.setBounds(0, centerY - NODE_HEIGHT / 2, w, NODE_HEIGHT)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Visible range helpers
    // ══════════════════════════════════════════════════════════════════

    private fun rebuildVisibleNodes() {
        removeAll()
        val end = minOf(visibleStartIndex + MAX_VISIBLE_NODES, nodes.size)
        for (i in visibleStartIndex until end) {
            add(createNode(i))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Node component
    // ══════════════════════════════════════════════════════════════════

    private fun createNode(index: Int): JPanel {
        val node = object : JPanel(null) {
            var rollover: Boolean = false

            override fun getPreferredSize() = Dimension(SIDEBAR_WIDTH, NODE_HEIGHT)
            override fun getMinimumSize() = preferredSize
            override fun getMaximumSize() = preferredSize

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val cx = width / 2.0
                val cy = height / 2.0

                // Hover glow
                if (rollover) {
                    g2.color = Color(26, 115, 232, 20)
                    g2.fillOval((cx - 10).toInt(), (cy - 10).toInt(), 20, 20)
                }

                // Dot
                g2.color = if (rollover) DOT_HOVER_COLOR else DOT_COLOR
                g2.fillOval((cx - DOT_RADIUS).toInt(), (cy - DOT_RADIUS).toInt(), DOT_RADIUS * 2, DOT_RADIUS * 2)

                g2.dispose()
            }
        }
        node.isOpaque = false
        node.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val fullText = nodes[index].text
        // Use Swing's built-in ToolTipManager — it paints globally, not clipped by parent
        node.toolTipText = fullText.toHtmlTooltip()

        node.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                node.rollover = true
                node.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                node.rollover = false
                node.repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                navigateTo(index)
            }
        })

        return node
    }

    // ══════════════════════════════════════════════════════════════════
    //  Navigation
    // ══════════════════════════════════════════════════════════════════

    private fun navigateTo(index: Int) {
        val data = nodes.getOrNull(index) ?: return
        SwingUtilities.invokeLater {
            val scrollPane = SwingUtilities.getAncestorOfClass(
                JBScrollPane::class.java, data.component
            ) as? JBScrollPane ?: return@invokeLater

            // The component is nested inside a wrapper panel (from wrapWithWidthConstraint).
            // Use the wrapper's y-coordinate (relative to the view = messagesPanel).
            val wrapper = data.component.parent ?: return@invokeLater
            val r = Rectangle(
                0,
                wrapper.y,
                wrapper.width.coerceAtLeast(1),
                wrapper.height.coerceAtLeast(1)
            )
            scrollPane.viewport.scrollRectToVisible(r)
            flashHighlight(data.component)
        }
    }

    private fun flashHighlight(comp: JComponent) {
        val origBg = comp.background
        comp.background = JBColor(0xD0E8FF, 0x3A4A6A)
        comp.repaint()
        Timer(1000) {
            comp.background = origBg
            comp.repaint()
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Painting
    // ══════════════════════════════════════════════════════════════════

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        g2.color = background
        g2.fillRect(0, 0, width, height)

        // Vertical dotted tree line through centre
        if (nodes.isNotEmpty()) {
            val cx = width / 2
            g2.color = LINE_COLOR
            g2.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0f, floatArrayOf(3f, 3f), 0f)
            g2.drawLine(cx, 0, cx, height)
        }

        g2.dispose()
    }
}

/** Convert plain text to HTML for multi-line Swing tooltip display. */
private fun String.toHtmlTooltip(): String {
    val escaped = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br>")
    return "<html><body style='width:320px; padding:4px; line-height:1.4'>$escaped</body></html>"
}
