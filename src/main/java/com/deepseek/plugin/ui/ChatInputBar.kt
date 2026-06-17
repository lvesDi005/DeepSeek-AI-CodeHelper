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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.CompoundBorder

/**
 * VSCode industrial dark themed chat input bar with 4-layer vertical layout.
 *
 * ┌──────────────────────────────────────────────────┐
 * │  100% open source                             ✕  │  ← Layer 1: Notification bar
 * ├──────────────────────────────────────────────────┤
 * │  📎                                              │  ← Layer 2: Toolbar (paperclip)
 * ├──────────────────────────────────────────────────┤
 * │  @引用文件，#调用智能Agent，!插入预设提示词...    │  ← Layer 3: Input area (70% height, resizable)
 * │                                                  │
 * │                                           ⠿     │  ← drag handle bottom-right
 * ├──────────────────────────────────────────────────┤
 * │  ⚡ Auto Mode [💬 问答 ▾]          [⚙] [▶ 发送] │  ← Layer 4: Status bar
 * └──────────────────────────────────────────────────┘
 */
class ChatInputBar(
    inputScrollPane: JBScrollPane,
    selectedCodePanel: JPanel?,
    fileAttachmentPanel: JPanel?,
    uploadButton: JComponent,
    modeSelector: JComponent,
    sendStopButton: JComponent
) : JPanel(BorderLayout()) {

    companion object {
        // ── VSCode industrial dark palette ──
        private val C_NOTIFICATION_BG = JBColor(Color(0xF5F5F5), Color(0x333333))
        private val C_TOOLBAR_BG = JBColor(Color(0xECECEC), Color(0x252526))
        private val C_INPUT_BG = JBColor(Color(0xFFFFFF), Color(0x1E1E1E))
        private val C_STATUS_BG = JBColor(Color(0xECECEC), Color(0x252526))
        private val C_BORDER = JBColor(Color(0xD4D4D4), Color(0x3C3C3C))
        private val C_BLUE = JBColor(Color(0x1A73E8), Color(0x4FC3F7))
        private val C_YELLOW = JBColor(Color(0xF9A825), Color(0xFFD54F))
        private val C_TEXT_SECONDARY = JBColor(Color(0x666666), Color(0x999999))
        private val C_TEXT_PRIMARY = JBColor(Color(0xCCCCCC), Color(0xCCCCCC))
        private val C_BUTTON_PRIMARY = JBColor(Color(0x1A73E8), Color(0x2979FF))
        private val C_BUTTON_SECONDARY_BORDER = JBColor(Color(0xC0C0C0), Color(0x555555))
    }

    init {
        isOpaque = false

        // ── Vertical container for the 4 layers ──
        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // ═══════ Layer 1: Notification bar ═══════
        stack.add(createNotificationBar())

        // ═══════ Layer 2: Toolbar ─ previews ═══════
        stack.add(createToolbar(selectedCodePanel, fileAttachmentPanel))

        // ═══════ Layer 3: Input area ═══════
        inputScrollPane.border = JBUI.Borders.empty()
        inputScrollPane.isOpaque = true
        inputScrollPane.background = C_INPUT_BG
        // Add a resize handle (corner component)
        inputScrollPane.setCorner(
            JBScrollPane.LOWER_RIGHT_CORNER,
            createResizeHandle(inputScrollPane)
        )
        stack.add(inputScrollPane)

        // ═══════ Layer 4: Status bar ═══════
        stack.add(createStatusBar(modeSelector, uploadButton, sendStopButton))

        add(stack, BorderLayout.CENTER)
    }

    // ── Layer 1 ────────────────────────────────────────────────

    private fun createNotificationBar(): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            background = C_NOTIFICATION_BG
            border = CompoundBorder(
                JBUI.Borders.customLine(C_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(4, 10, 4, 4)
            )
            isOpaque = true
            minimumSize = Dimension(0, 28)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 28)
        }

        // Left: blue text
        val label = JLabel("  100% open source").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = C_BLUE
            icon = AllIcons.General.BalloonInformation
            iconTextGap = 6
        }
        bar.add(label, BorderLayout.WEST)

        // Right: close × button
        val closeBtn = ActionButton(
            object : AnAction(null, null, AllIcons.Actions.Close) {
                override fun actionPerformed(e: AnActionEvent) {
                    bar.isVisible = false
                }
            },
            Presentation().apply {
                icon = AllIcons.Actions.Close
                description = "关闭"
            },
            ActionPlaces.TOOLBAR,
            Dimension(18, 18)
        ).withTooltip("关闭")

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(closeBtn)
        }
        bar.add(rightPanel, BorderLayout.EAST)

        return bar
    }

    // ── Layer 2 ────────────────────────────────────────────────

    private fun createToolbar(
        selectedCodePanel: JPanel?,
        fileAttachmentPanel: JPanel?
    ): JPanel {
        val toolbar = JPanel(BorderLayout()).apply {
            background = C_TOOLBAR_BG
            isOpaque = true
            border = CompoundBorder(
                JBUI.Borders.customLine(C_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(4, 6, 4, 6)
            )
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 36)
        }

        // Preview panels (optional, stacked vertically)
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

    // ── Layer 3: Resize Handle ────────────────────────────────

    private fun createResizeHandle(scrollPane: JBScrollPane): JPanel {
        val handle = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics
                try {
                    g2.color = C_TEXT_SECONDARY
                    val x = width - 12
                    val y = height - 8
                    // Draw 3 small dots as a resize grip
                    for (i in 0..2) {
                        for (j in 0..i) {
                            g2.fillRect(x + j * 4, y + i * 4, 2, 2)
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
            background = C_INPUT_BG
            isOpaque = true

            // Drag to resize
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val parent = scrollPane.parent
                    if (parent != null) {
                        val newHeight = scrollPane.height + e.y
                        val constrained = newHeight.coerceIn(60, 400)
                        scrollPane.preferredSize = Dimension(scrollPane.width, constrained)
                        scrollPane.revalidate()
                    }
                }
            })
        }
        return handle
    }

    // ── Layer 4 ────────────────────────────────────────────────

    private fun createStatusBar(
        modeSelector: JComponent,
        uploadButton: JComponent,
        sendStopButton: JComponent
    ): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            background = C_STATUS_BG
            isOpaque = true
            border = CompoundBorder(
                JBUI.Borders.customLine(C_BORDER, 1, 0, 0, 0),
                JBUI.Borders.empty(6, 8, 6, 8)
            )
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 44)
        }

        // ── Left: mode selector + upload ──
        val leftGroup = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(modeSelector)
            add(uploadButton)
        }
        bar.add(leftGroup, BorderLayout.WEST)

        // ── Right: send button ──
        val rightGroup = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
        }

        // Primary send button — visually prominent
        sendStopButton.apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = Color.WHITE
            background = C_BUTTON_PRIMARY
            border = JBUI.Borders.empty(6, 18, 6, 18)
            isOpaque = true
        }
        rightGroup.add(sendStopButton)

        bar.add(rightGroup, BorderLayout.EAST)

        return bar
    }
}
