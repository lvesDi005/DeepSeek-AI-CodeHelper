package com.deepseek.plugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
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
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.CompoundBorder

/**
 * 现代化聊天输入栏。
 *
 * ┌──────────────────────────────────────────────────┐
 * │  📎 选中代码/文件预览区域                          │  ← Toolbar (previews)
 * ├──────────────────────────────────────────────────┤
 * │  ┌────────────────────────────────────────────┐  │
 * │  │  输入消息...  @引用文件  ·  Enter发送      │  │  ← 圆角输入区
 * │  │                                            │  │
 * │  │                                      ⠿     │  │  ← resize handle
 * │  └────────────────────────────────────────────┘  │
 * ├──────────────────────────────────────────────────┤
 * │  💬 问答 ▾               📤 [▶ 发送 (Enter)]    │  ← Status bar
 * └──────────────────────────────────────────────────┘
 */
class ChatInputBar(
    inputScrollPane: JBScrollPane,
    selectedCodePanel: JPanel?,
    fileAttachmentPanel: JPanel?,
    uploadButton: JComponent,
    translateButton: JComponent,
    modeSelector: JComponent,
    sendStopButton: JComponent,
    private val onResizeRequest: ((deltaY: Int) -> Unit)? = null
) : JPanel(BorderLayout()) {

    companion object {
        private val C_INPUT_BG = JBColor(Color(0xFFFFFF), Color(0x1E1E1E))
        private val C_INPUT_BORDER = JBColor(Color(0xD0D0D0), Color(0x3C3C3C))
        private val C_INPUT_BORDER_FOCUS = JBColor(0x1A73E8, 0x64B5F6)
        private val C_TOOLBAR_BG = JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
        private val C_STATUS_BG = JBColor(Color(0xF5F5F5), Color(0x2D2D2D))
        private val C_BORDER = JBColor(Color(0xE0E0E0), Color(0x3C3C3C))
        private val C_BUTTON_PRIMARY = JBColor(0x1A73E8, 0x2979FF)
        private val C_TEXT_SECONDARY = JBColor(0x999999, 0x777777)
    }

    init {
        isOpaque = false

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // ═══════ Layer 1: Toolbar — previews ═══════
        stack.add(createToolbar(selectedCodePanel, fileAttachmentPanel))

        // ═══════ Layer 2: Rounded input area ═══════
        stack.add(createRoundedInputWrapper(inputScrollPane))

        // ═══════ Layer 3: Status bar ═══════
        stack.add(createStatusBar(modeSelector, uploadButton, translateButton, sendStopButton))

        add(stack, BorderLayout.CENTER)
    }

    private fun createToolbar(
        selectedCodePanel: JPanel?,
        fileAttachmentPanel: JPanel?
    ): JPanel {
        val toolbar = JPanel(BorderLayout()).apply {
            background = C_TOOLBAR_BG
            isOpaque = true
            border = CompoundBorder(
                JBUI.Borders.customLine(C_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(2, 6, 2, 6)
            )
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 32)
        }

        if (selectedCodePanel != null || fileAttachmentPanel != null) {
            val previewStack = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                if (selectedCodePanel != null) add(selectedCodePanel)
                if (fileAttachmentPanel != null) add(fileAttachmentPanel)
            }
            toolbar.add(previewStack, BorderLayout.CENTER)
        }

        return toolbar
    }

    private fun createRoundedInputWrapper(scrollPane: JBScrollPane): JPanel {
        scrollPane.isOpaque = false
        scrollPane.viewport.isOpaque = false
        scrollPane.border = JBUI.Borders.empty()

        scrollPane.setCorner(JBScrollPane.LOWER_RIGHT_CORNER, createResizeHandle())

        val wrapper = object : JPanel(BorderLayout()) {
            private var focused = false

            init {
                scrollPane.components.forEach { comp ->
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
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val arc = 10
                g2.color = C_INPUT_BG
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)

                g2.color = if (focused) C_INPUT_BORDER_FOCUS else C_INPUT_BORDER
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

                g2.dispose()
            }
        }.apply {
            layout = BorderLayout()
            isOpaque = false
            border = JBUI.Borders.empty(8, 10, 4, 10)
            add(scrollPane, BorderLayout.CENTER)
        }

        return wrapper
    }

    private fun createResizeHandle(): JPanel {
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                try {
                    g2.color = C_TEXT_SECONDARY
                    val x = width - 14
                    val y = height - 10
                    for (i in 0..2) {
                        for (j in 0..i) {
                            g2.fillRoundRect(x + j * 4, y + i * 4, 2, 2, 1, 1)
                        }
                    }
                } finally {
                    g2.dispose()
                }
            }
        }.apply {
            preferredSize = Dimension(20, 20)
            minimumSize = Dimension(20, 20)
            cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
            isOpaque = false

            var startY = 0
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { startY = e.y }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val delta = e.y - startY
                    if (onResizeRequest != null) {
                        onResizeRequest(delta)
                    }
                }
            })
        }
    }

    private fun createStatusBar(
        modeSelector: JComponent,
        uploadButton: JComponent,
        translateButton: JComponent,
        sendStopButton: JComponent
    ): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            background = C_STATUS_BG
            isOpaque = true
            border = CompoundBorder(
                JBUI.Borders.customLine(C_BORDER, 1, 0, 0, 0),
                JBUI.Borders.empty(6, 10, 6, 8)
            )
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 44)
        }

        // ── 左侧：4 个控件紧凑排列 ──
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(modeSelector)
            add(Box.createHorizontalStrut(6))
            add(uploadButton)
            add(Box.createHorizontalStrut(6))
            add(translateButton)
        }
        bar.add(leftGroup, BorderLayout.WEST)

        // ── 右侧：发送按钮 ──
        sendStopButton.apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = Color.WHITE
            background = C_BUTTON_PRIMARY
            border = JBUI.Borders.empty(6, 18, 6, 18)
            isOpaque = true
        }
        bar.add(sendStopButton, BorderLayout.EAST)

        return bar
    }
}
