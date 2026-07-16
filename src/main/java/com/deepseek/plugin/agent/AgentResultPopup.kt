package com.deepseek.plugin.agent

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.ResponseSegment
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 在编辑器选中代码下方以绿色背景弹窗显示 AI 响应内容。
 *
 * 布局：
 * ┌─────────────────────────┐
 * │ The `ComponentActivity` │
 * │ class is part of...     │  ← 文本正文（可上下左右滚动）
 * └─────────────────────────┘
 */
object AgentResultPopup {

    // ── 配色 ──
    private val GREEN_BG = JBColor(Color(0xE8F5E9), Color(0x1B5E20))
    private val GREEN_BORDER = JBColor(Color(0x4CAF50), Color(0x388E3C))
    private val TEXT_COLOR = JBColor(Color(0x212121), Color(0xE0E0E0))
    private val ACCENT_COLOR = JBColor(Color(0x2E7D32), Color(0xA5D6A7))

    private var activeHint: Balloon? = null

    /** 流式模式：当前正在追加内容的文本区域 */
    private var streamingTextArea: JBTextArea? = null
    /** 流式模式：当前编辑器（用于定位弹窗） */
    private var streamingEditor: Editor? = null

    fun dismiss() {
        activeHint?.dispose()
        activeHint = null
        streamingTextArea = null
        streamingEditor = null
    }

    /**
     * 在选中代码起始行下方显示加载提示。
     */
    fun showLoadingHint(editor: Editor) {
        dismiss()
        streamingEditor = editor

        val label = JLabel("  ⏳ ${I18n.tr("agent.calling")}  ").apply {
            font = JBUI.Fonts.create("SansSerif", 13).deriveFont(Font.BOLD)
            foreground = ACCENT_COLOR
            horizontalAlignment = SwingConstants.CENTER
        }

        val hintPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            background = GREEN_BG
            border = JBUI.Borders.empty(8, 16)
            add(label)
        }

        activeHint = JBPopupFactory.getInstance().createBalloonBuilder(hintPanel)
            .setFillColor(GREEN_BG)
            .setBorderColor(GREEN_BORDER)
            .setBorderInsets(JBUI.insets(0))
            .setFadeoutTime(0)
            .setHideOnKeyOutside(true)
            .setHideOnAction(false)
            .createBalloon()
            .also { it.show(belowSelectionStart(editor), Balloon.Position.below) }
    }

    /**
     * 在选中代码起始行下方显示结果弹窗。
     *
     * @param editor       当前编辑器
     * @param project      当前项目
     * @param originalCode 用户选中的原始代码（用作标题头）
     * @param response     AI 响应内容（MD 格式）
     */
    fun showResult(editor: Editor, project: Project, originalCode: String, response: String) {
        dismiss()

        val rootPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = GREEN_BG
            isOpaque = true
        }

        // ── 正文：解析响应并分段渲染 ──
        val segments = CodeBlockCard.parseResponse(response)
        val bodyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 12, 8, 12)
            background = GREEN_BG
            isOpaque = true
        }

        for (segment in segments) {
            when (segment) {
                is ResponseSegment.Text -> {
                    bodyPanel.add(createTextSegment(segment.content))
                }
                is ResponseSegment.Code -> {
                    bodyPanel.add(Box.createVerticalStrut(4))
                    bodyPanel.add(createTextSegment(segment.content))
                    bodyPanel.add(Box.createVerticalStrut(4))
                }
                is ResponseSegment.Table -> {
                    bodyPanel.add(Box.createVerticalStrut(4))
                    bodyPanel.add(createTextSegment(tableToText(segment)))
                    bodyPanel.add(Box.createVerticalStrut(4))
                }
            }
        }

        rootPanel.add(bodyPanel)

        // 限制最大宽度，高度由内容自动撑开，超出时纵向滚动
        rootPanel.maximumSize = Dimension(660, Int.MAX_VALUE)

        val scrollPane = JBScrollPane(rootPanel).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(520, 300)
        }

        activeHint = JBPopupFactory.getInstance().createBalloonBuilder(scrollPane)
            .setFillColor(GREEN_BG)
            .setBorderColor(GREEN_BORDER)
            .setBorderInsets(JBUI.insets(4))
            .setFadeoutTime(0)
            .setCloseButtonEnabled(true)
            .setHideOnKeyOutside(true)
            .setHideOnAction(true)
            .setShadow(true)
            .createBalloon()
            .also { it.show(belowSelectionStart(editor), Balloon.Position.below) }
    }

    // ── 流式输出 ──

    /**
     * 追加流式 token 到当前弹窗。首次调用时自动关闭加载提示并创建弹窗。
     */
    fun appendStreamingContent(token: String) {
        if (token.isEmpty()) return

        if (streamingTextArea == null) {
            // 首次 token：关闭加载提示，创建流式弹窗
            val editor = streamingEditor ?: return
            dismiss()

            val textArea = JBTextArea().apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = JBUI.Fonts.create("SansSerif", 13)
                margin = JBUI.insets(6, 12, 6, 12)
                border = JBUI.Borders.empty()
                background = GREEN_BG
                foreground = TEXT_COLOR
                isOpaque = true
            }
            streamingTextArea = textArea

            val rootPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = GREEN_BG
                isOpaque = true
                add(textArea)
            }

            val scrollPane = JBScrollPane(rootPanel).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(520, 300)
            }

            activeHint = JBPopupFactory.getInstance().createBalloonBuilder(scrollPane)
                .setFillColor(GREEN_BG)
                .setBorderColor(GREEN_BORDER)
                .setBorderInsets(JBUI.insets(4))
                .setFadeoutTime(0)
                .setCloseButtonEnabled(true)
                .setHideOnKeyOutside(true)
                .setHideOnAction(true)
                .setShadow(true)
                .createBalloon()
                .also { it.show(belowSelectionStart(editor), Balloon.Position.below) }
        }
        streamingTextArea?.append(token)
    }

    /** 流式输出完成，清理引用 */
    fun finishStreaming() {
        streamingTextArea = null
        streamingEditor = null
    }

    // ── 内部方法 ──

    /**
     * 使用 IDEA 原生 JBTextArea 渲染文本段，绿色主题。
     */
    private fun createTextSegment(text: String): JBTextArea {
        return JBTextArea(text.trim()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("SansSerif", 13)
            margin = JBUI.insets(2, 0, 4, 0)
            border = JBUI.Borders.empty()
            background = GREEN_BG
            foreground = TEXT_COLOR
            isOpaque = true
        }
    }

    /** 将表格段转为纯文本（Markdown 表格格式） */
    private fun tableToText(table: ResponseSegment.Table): String = buildString {
        appendLine(table.headers.joinToString(" | "))
        appendLine(table.headers.joinToString(" | ") { "---" })
        for (row in table.rows) {
            appendLine(row.joinToString(" | "))
        }
    }

    /** 定位到选中代码正中间的下方，不遮挡选中代码 */
    private fun belowSelectionStart(editor: Editor): RelativePoint {
        if (!editor.selectionModel.hasSelection()) {
            val pos = editor.offsetToVisualPosition(editor.caretModel.offset)
            val pt = editor.visualPositionToXY(pos)
            return RelativePoint(editor.contentComponent, Point(pt.x, pt.y))
        }
        val startPos = editor.offsetToVisualPosition(editor.selectionModel.selectionStart)
        val endPos = editor.offsetToVisualPosition(editor.selectionModel.selectionEnd)
        val startPt = editor.visualPositionToXY(startPos)
        val endPt = editor.visualPositionToXY(endPos)
        // 水平居中：取选中区域首尾的中间 X 坐标
        val centerX = (startPt.x + endPt.x) / 2
        // 垂直：放在选中代码最后一行下方（避免遮挡）
        val belowY = endPt.y + editor.lineHeight
        return RelativePoint(editor.contentComponent, Point(centerX, belowY))
    }
}
