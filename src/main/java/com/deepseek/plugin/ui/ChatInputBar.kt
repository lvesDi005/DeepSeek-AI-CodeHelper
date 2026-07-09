package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import com.deepseek.plugin.i18n.I18n

/**
 * 一体化聊天输入组件。
 *
 * 布局（自上而下）：
 * ┌──────────────────────────────────────────────┐  ← 拖拽分隔栏（浅青色，1/3宽居中，未聚焦时可见）
 * ├──────────────────────────────────────────────┤
 * │  📄 JenkinsDemoApplication.java: 6-6    ✕   │  ← 文件标签整合在输入框顶部
 * │  📄 app.java  2.3 KB                    ✕   │
 * │                                              │
 * │  输入消息... @引用文件 · Enter发送 ·...       │  ← 主题自适应输入区
 * │                                              │
 * ├──────────────────────────────────────────────┤
 * │  💬 问答 ▾               📤 [▶ 发送 (Enter)]  │  ← 状态栏
 * └──────────────────────────────────────────────┘
 *
 * 整块面板底色主题自适应，下半部分主题自适应输入区。
 * 顶部纤细浅青色分隔栏可拖拽调整高度，双击重置。
 */
class ChatInputBar(
    private val inputScrollPane: JBScrollPane,
    selectedCodePanel: JPanel?,
    fileAttachmentPanel: JPanel?,
    uploadButton: JComponent,
    translateButton: JComponent,
    settingsButton: JComponent,
    sendStopButton: JComponent
) : JPanel(BorderLayout()) {

    /** 回调：拖拽调整输入区高度，参数为目标高度（像素）；Int.MAX_VALUE 表示双击重置 */
    var onResizeRequest: ((Int) -> Unit)? = null

    companion object {
        private val C_DARK_BG = JBColor(0xE8E8E8, 0x1E1E1E)
        private val C_INPUT_BG = JBColor(0xFFFFFF, 0x2B2B2B)
        private val C_INPUT_BORDER = JBColor(0xD0D0D0, 0x3C3C3C)
        private val C_INPUT_BORDER_FOCUS = JBColor(0x1A73E8, 0x64B5F6)
        private val C_DRAG_BAR = JBColor(0x80DEEA, 0x4DD0E1)
        private val C_DRAG_BAR_HOVER = JBColor(0x4DD0E1, 0x26C6DA)
        private val C_STATUS_BG = JBColor(0xE8E8E8, 0x2D2D2D)
        /** 发送按钮行背景色 — 比输入区 (C_INPUT_BG) 暗一个色阶 */
        private val C_SEND_BG = JBColor(0xE8E8E8, 0x1E1E1E)
    }

    init {
        isOpaque = true
        background = C_DARK_BG

        // ═══════ Layer 1: Drag handle bar (top, always-present drag zone) ═══════
        add(createDragZone(), BorderLayout.NORTH)

        // ═══════ Layer 2: Body — 统一圆角背景 + 焦点蓝色光边 ═══════
        val body = object : JPanel() {
            private var focused = false

            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false

                // 监听输入框焦点，控制外层面板边框颜色
                inputScrollPane.components.forEach { comp ->
                    if (comp is javax.swing.JViewport) {
                        comp.components.forEach { c ->
                            if (c is javax.swing.text.JTextComponent) {
                                c.addFocusListener(object : java.awt.event.FocusAdapter() {
                                    override fun focusGained(e: java.awt.event.FocusEvent) {
                                        focused = true
                                        repaint()
                                    }
                                    override fun focusLost(e: java.awt.event.FocusEvent) {
                                        focused = false
                                        repaint()
                                    }
                                })
                            }
                        }
                    }
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = 8
                    // 整体填充（输入区背景色，状态栏区域将被 C_SEND_BG 覆盖）
                    g2.color = C_INPUT_BG
                    g2.fillRoundRect(0, 0, width, height, arc, arc)
                } finally {
                    g2.dispose()
                }
            }

            override fun paint(g: Graphics) {
                super.paint(g)
                // 在子组件绘制完成后画边框，确保不被状态栏覆盖
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = 8
                    g2.color = if (focused) C_INPUT_BORDER_FOCUS else C_INPUT_BORDER
                    g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                } finally {
                    g2.dispose()
                }
            }
        }

        // ── Input area: theme-adaptive background, file tags at top ──
        body.add(createInputArea(inputScrollPane, selectedCodePanel, fileAttachmentPanel))

        // ── Status bar ──
        body.add(createStatusBar(settingsButton, uploadButton, translateButton, sendStopButton))

        add(body, BorderLayout.CENTER)
    }

    // ──────────────────────────────────────────────
    //  Drag Zone (top resize handle)
    // ──────────────────────────────────────────────

    private fun createDragZone(): JPanel {
        return object : JPanel() {
            private var hovered = false
            private var inputFocused = false
            private var dragStartY = 0
            private var dragStartHeight = 0

            init {
                preferredSize = Dimension(Short.MAX_VALUE.toInt(), 8)
                minimumSize = Dimension(0, 8)
                maximumSize = Dimension(Short.MAX_VALUE.toInt(), 8)
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                toolTipText = I18n.tr("input.drag.resize")

                // 监听输入框的焦点变化，控制分隔栏的显隐
                inputScrollPane.components.forEach { comp: java.awt.Component ->
                    if (comp is javax.swing.JViewport) {
                        comp.components.forEach { c ->
                            if (c is javax.swing.text.JTextComponent) {
                                c.addFocusListener(object : java.awt.event.FocusAdapter() {
                                    override fun focusGained(e: java.awt.event.FocusEvent) {
                                        inputFocused = true
                                        repaint()
                                    }

                                    override fun focusLost(e: java.awt.event.FocusEvent) {
                                        inputFocused = false
                                        repaint()
                                    }
                                })
                            }
                        }
                    }
                }

                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        dragStartY = e.yOnScreen
                        dragStartHeight = this@ChatInputBar.parent?.height ?: height
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        hovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        hovered = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2) {
                            onResizeRequest?.invoke(Int.MAX_VALUE)
                        }
                    }
                })

                addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseDragged(e: MouseEvent) {
                        // 绝对高度计算：从拖拽起点计算新高度，手感更顺滑
                        val delta = e.yOnScreen - dragStartY
                        val newHeight = dragStartHeight - delta
                        onResizeRequest?.invoke(newHeight)
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // 输入框聚焦时隐藏分隔栏
                if (inputFocused) return

                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                    // 分隔栏宽度为父容器 1/3，水平居中
                    val parentW = parent?.width ?: width
                    val barWidth = (parentW / 3).coerceAtLeast(60)
                    val barX = (width - barWidth) / 2
                    val barY = (height - 3) / 2

                    g2.color = if (hovered) C_DRAG_BAR_HOVER else C_DRAG_BAR
                    g2.fillRoundRect(barX, barY, barWidth, 3, 2, 2)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Input Area (theme-adaptive, file tags at top)
    // ──────────────────────────────────────────────

    private fun createInputArea(
        scrollPane: JBScrollPane,
        selectedCodePanel: JPanel?,
        fileAttachmentPanel: JPanel?
    ): JPanel {
        val wrapper = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 8)
        }

        // ── Tags row: file/code badges at top of input ──
        val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
        }
        var hasTags = false
        if (selectedCodePanel != null) {
            tagsPanel.add(selectedCodePanel)
            hasTags = true
        }
        if (fileAttachmentPanel != null) {
            tagsPanel.add(fileAttachmentPanel)
            hasTags = true
        }
        if (hasTags) {
            wrapper.add(tagsPanel)
            // 固定标签区域高度，不随输入框拉伸收缩改变
            val fixedH = tagsPanel.preferredSize.height
            tagsPanel.maximumSize = Dimension(Short.MAX_VALUE.toInt(), fixedH)
            tagsPanel.minimumSize = Dimension(0, fixedH)
        }

        // ── Text input area (transparent, rendering on theme-adaptive bg) ──
        scrollPane.isOpaque = false
        scrollPane.viewport.isOpaque = false
        scrollPane.border = JBUI.Borders.empty()
        wrapper.add(scrollPane)

        return wrapper
    }

    // ──────────────────────────────────────────────
    //  Status Bar
    // ──────────────────────────────────────────────

    private fun createStatusBar(
        settingsButton: JComponent,
        uploadButton: JComponent,
        translateButton: JComponent,
        sendStopButton: JComponent
    ): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            background = C_SEND_BG
            isOpaque = true
            border = JBUI.Borders.empty(6, 10, 6, 8)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 44)
        }

        // ── 左侧：设置 + 上传 + 翻译 ──
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(settingsButton)
            add(Box.createHorizontalStrut(6))
            add(uploadButton)
            add(Box.createHorizontalStrut(6))
            add(translateButton)
        }
        bar.add(leftGroup, BorderLayout.WEST)

        // ── 右侧：发送/停止按钮（纯 LaF 原生 — 圆角、主题自适应、悬停/按下状态均由 LaF 处理）──
        sendStopButton.apply {
            font = font.deriveFont(Font.BOLD, 12f)
            // 不覆盖 foreground / background / border / isOpaque，
            // 完全依赖 LaF 默认值，实现：
            // • 圆角矩形（各 LaF 内置）
            // • 背景/前景/边框随浅色/深色主题自动适配
            // • 正确的悬停、按下、聚焦、禁用视觉状态
        }
        bar.add(sendStopButton, BorderLayout.EAST)

        return bar
    }
}
