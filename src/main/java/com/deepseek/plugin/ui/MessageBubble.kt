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
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.Border
import javax.swing.text.DefaultCaret

/**
 * A bubble-style message component for chat. Supports three roles:
 *
 * - **[USER]**: Blue accent, "You" avatar, plain text content.
 * - **[ASSISTANT]**: Green accent, "AI" avatar, parsed text (Markdown) + code blocks.
 * - **[STREAMING]**: Gray bottom separator, "AI" avatar, scrollable text area.
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
        // Set a 1-pixel empty border for the shadow "bleed"
        border = JBUI.Borders.empty(0, 0, 1, 0)
        isOpaque = false

        when (role) {
            Role.USER -> setupUserMessage(content)
            Role.ASSISTANT -> setupAssistantMessage(content, segments)
            Role.STREAMING -> setupStreamingArea()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Custom painting — rounded background + shadow
    // ══════════════════════════════════════════════════════════════════

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width
        val h = height
        val arc = 12
        val shadowOffset = 1

        // ── Shadow ──
        g2.color = Color(0, 0, 0, if (JBColor.isBright()) 10 else 40)
        g2.fillRoundRect(0, shadowOffset, w - 1, h - shadowOffset - 1, arc, arc)

        // ── Background ──
        g2.color = background
        g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc)

        g2.dispose()
    }

    // ══════════════════════════════════════════════════════════════════
    //  User message — blue theme
    // ══════════════════════════════════════════════════════════════════

    private fun setupUserMessage(content: String) {
        background = JBColor(0xE8F0FE, 0x1A2533)

        val headerPanel = createHeaderRow(
            avatarLetter = "U",
            avatarBg = JBColor(0x1A73E8, 0x4A7FE8),
            labelText = "You",
            labelColor = JBColor(0x1A73E8, 0x64B5F6)
        )

        val textArea = JBTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 13)
            background = Color(0, 0, 0, 0) // transparent
            margin = JBUI.insets(2, 36, 2, 0) // indent to align with header text
            border = JBUI.Borders.empty()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val inner = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(textArea, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Assistant message — green theme, Markdown + CodeBlockCard
    // ══════════════════════════════════════════════════════════════════

    private fun setupAssistantMessage(
        content: String,
        segments: List<ResponseSegment>?
    ) {
        background = JBColor(0xF5FBF5, 0x252525)

        val headerPanel = createHeaderRow(
            avatarLetter = "D",
            avatarBg = JBColor(0x2E7D32, 0x66BB6A),
            labelText = "DeepSeek",
            labelColor = JBColor(0x1B5E20, 0x81C784)
        )

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
                        fgColor = JBColor(0x333333, 0xD4D4D4),
                        bgColor = null // transparent
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
                    contentBody.add(Box.createVerticalStrut(4))
                }
            }
        }

        if (contentBody.componentCount == 0) {
            // If only code blocks or empty, add a spacer
            contentBody.add(Box.createVerticalStrut(2))
        }

        val inner = JPanel(BorderLayout()).apply {
            isOpaque = false
            val topMargin = JBUI.Borders.empty(4, 0, 0, 0)
            val headerWithMargin = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = topMargin
                add(headerPanel, BorderLayout.CENTER)
            }
            add(headerWithMargin, BorderLayout.NORTH)
            add(contentBody, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Streaming area — green theme, scrollable text
    // ══════════════════════════════════════════════════════════════════

    private fun setupStreamingArea() {
        background = JBColor(0xF5FBF5, 0x252525)

        val headerPanel = createHeaderRow(
            avatarLetter = "D",
            avatarBg = JBColor(0x2E7D32, 0x66BB6A),
            labelText = "DeepSeek",
            labelColor = JBColor(0x1B5E20, 0x81C784)
        )

        streamTextArea!!.apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 13)
            background = Color(0, 0, 0, 0) // transparent
            margin = JBUI.insets(2, 36, 2, 0) // indent to align with header text
            border = JBUI.Borders.empty()
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
            val topMargin = JBUI.Borders.empty(4, 0, 0, 0)
            val headerWithMargin = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = topMargin
                add(headerPanel, BorderLayout.CENTER)
            }
            add(headerWithMargin, BorderLayout.NORTH)
            add(streamScrollPane, BorderLayout.CENTER)
        }
        add(inner, BorderLayout.CENTER)
    }

    // ══════════════════════════════════════════════════════════════════
    //  Header row — avatar circle + label (shared across roles)
    // ══════════════════════════════════════════════════════════════════

    private fun createHeaderRow(
        avatarLetter: String,
        avatarBg: Color,
        labelText: String,
        labelColor: Color
    ): JPanel {
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        // ── Timestamp (right side) ──
        val timeLabel = JLabel(timestamp).apply {
            font = font.deriveFont(Font.PLAIN, 9f)
            foreground = JBColor(0x999999, 0x777777)
        }

        // ── Avatar: colored circle with initial ──
        val avatarSize = 24
        val avatar = object : JPanel(null) {
            override fun getPreferredSize() = Dimension(avatarSize, avatarSize)
            override fun getMinimumSize() = preferredSize
            override fun getMaximumSize() = preferredSize

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = avatarBg
                g2.fillOval(0, 0, avatarSize - 1, avatarSize - 1)
                g2.color = JBColor.WHITE
                g2.font = g2.font.deriveFont(Font.BOLD, 11f)
                val fm = g2.fontMetrics
                val textX = (avatarSize - fm.stringWidth(avatarLetter)) / 2f
                val textY = (avatarSize + fm.ascent - fm.descent) / 2f
                g2.drawString(avatarLetter, textX, textY)
                g2.dispose()
            }
        }
        avatar.isOpaque = false

        // ── Label ──
        val label = JLabel(labelText).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = labelColor
        }

        val leftPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 0, 0)
            add(avatar, BorderLayout.WEST)
            add(Box.createHorizontalStrut(6), BorderLayout.CENTER)
            add(label, BorderLayout.EAST)
        }
        row.add(leftPanel, BorderLayout.WEST)
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
