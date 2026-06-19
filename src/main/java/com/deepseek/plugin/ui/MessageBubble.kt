package com.deepseek.plugin.ui

import com.deepseek.plugin.ui.CodeBlockCard.Companion.parseResponse
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.text.DefaultCaret

/**
 * A minimal chat message component.
 *
 * Design (inspired by modern chat UIs):
 * - Transparent background — no colored bubbles, no shadow
 * - Plain text role label ("You" / "DeepSeek") instead of avatar circles
 * - Subtle bottom separator between messages
 * - Content fills the full width naturally
 */
class MessageBubble(
    private val project: Any? = null,
    role: Role,
    content: String = "",
    segments: List<ResponseSegment>? = null
) : JPanel(BorderLayout()) {

    /** The time this bubble was created, formatted for display. */
    val timestamp: String = formatTimestamp(System.currentTimeMillis())

    enum class Role {
        USER,
        ASSISTANT,
        STREAMING
    }

    /** Streaming: text area for incremental tokens. */
    val streamTextArea: JBTextArea? = if (role == Role.STREAMING) JBTextArea() else null

    /** Streaming: scroll pane wrapping the text area. */
    val streamScrollPane: JBScrollPane? = if (role == Role.STREAMING) JBScrollPane() else null

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
        // Subtle bottom separator line
        border = JBUI.Borders.customLine(JBColor(0xE8E8E8, 0x333333), 0, 0, 1, 0)

        when (role) {
            Role.USER -> setupUserMessage(content)
            Role.ASSISTANT -> setupAssistantMessage(content, segments)
            Role.STREAMING -> setupStreamingArea()
        }
    }

    // ================================================================
    //  User message
    // ================================================================

    private fun setupUserMessage(content: String) {
        val headerPanel = createHeaderRow("You", JBColor(0x333333, 0xBBBBBB))
        val textArea = JBTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 13)
            background = Color(0, 0, 0, 0)
            margin = JBUI.insets(0, 0, 0, 0)
            border = JBUI.Borders.empty()
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val inner = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 0, 6)
            add(headerPanel, BorderLayout.NORTH)
            add(textArea, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ================================================================
    //  Assistant message — Markdown + CodeBlockCard
    // ================================================================

    private fun setupAssistantMessage(
        content: String,
        segments: List<ResponseSegment>?
    ) {
        val headerPanel = createHeaderRow("DeepSeek", JBColor(0x1A73E8, 0x64B5F6))

        val contentBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val resolvedSegments = segments ?: parseResponse(content)

        for (segment in resolvedSegments) {
            when (segment) {
                is ResponseSegment.Text -> {
                    val mdPane = MarkdownRenderer.createPane(
                        markdownText = segment.content,
                        fontSize = 13,
                        fgColor = JBColor(0x1A1A1A, 0xE0E0E0),
                        bgColor = null
                    )
                    contentBody.add(mdPane)
                }

                is ResponseSegment.Code -> {
                    val card = CodeBlockCard(
                        project = project as? com.intellij.openapi.project.Project,
                        code = segment.content,
                        language = segment.language,
                        showInsertButton = true
                    )
                    contentBody.add(Box.createVerticalStrut(4))
                    contentBody.add(card)
                    contentBody.add(Box.createVerticalStrut(2))
                }
            }
        }

        if (contentBody.componentCount == 0) {
            contentBody.add(Box.createVerticalStrut(2))
        }

        val inner = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 0, 6)
            add(headerPanel, BorderLayout.NORTH)
            add(contentBody, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ================================================================
    //  Streaming area
    // ================================================================

    private fun setupStreamingArea() {
        val headerPanel = createHeaderRow("DeepSeek", JBColor(0x1A73E8, 0x64B5F6))

        streamTextArea!!.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 13)
            background = Color(0, 0, 0, 0)
            margin = JBUI.insets(0, 0, 0, 0)
            border = JBUI.Borders.empty()
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            (caret as DefaultCaret).updatePolicy = DefaultCaret.ALWAYS_UPDATE
        }

        streamScrollPane!!.apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            setViewportView(streamTextArea)
            preferredSize = Dimension(100, 80)
        }

        val inner = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 6, 0, 6)
            add(headerPanel, BorderLayout.NORTH)
            add(streamScrollPane, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ================================================================
    //  Header row — simple text label (no avatar circle)
    // ================================================================

    private fun createHeaderRow(labelText: String, labelColor: Color): JPanel {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        val label = JLabel(labelText).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = labelColor
        }

        // Timestamp
        val timeLabel = JLabel(timestamp).apply {
            font = font.deriveFont(Font.PLAIN, 9f)
            foreground = JBColor(0x999999, 0x666666)
        }

        row.add(label, BorderLayout.WEST)
        row.add(timeLabel, BorderLayout.EAST)

        return row
    }

    companion object {
        /** Format a millis timestamp into a short time string (HH:MM). */
        private fun formatTimestamp(millis: Long): String {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return fmt.format(java.util.Date(millis))
        }
    }
}
