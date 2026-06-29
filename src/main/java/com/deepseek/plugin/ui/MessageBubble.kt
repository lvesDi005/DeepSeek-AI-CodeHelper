package com.deepseek.plugin.ui

import com.deepseek.plugin.ui.CodeBlockCard.Companion.parseResponse
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultCaret

/**
 * 深色扁平化聊天消息气泡组件。
 *
 * 设计语言：
 * ─────────────────────────────────────────────
 * 【用户消息】 → 右对齐卡片
 *
 *  ┌──────────────────────────────────────┐
 *  │  (n)  14:32                     🗑  │  ← 橙色头像 + 时间 + 删除
 *  │  ☕ index.js   ☕ app.css             │  ← 文件标签
 *  │  ──────────────────────────────────  │  ← 分割线
 *  │  正文内容...                          │  ← 白色正文
 *  └──────────────────────────────────────┘
 *
 * ─────────────────────────────────────────────
 * 【AI 回复】   → 左对齐卡片
 *
 *  ┌──────────────────────────────────────┐
 *  │  ○  DP Helper                        │  ← 环形图标 + 名称
 *  │                                      │
 *  │  回复内容...                          │
 *  │                                      │
 *  │  ┌────────────────────────┐          │
 *  │  │  代码块 (CodeBlockCard)  │          │
 *  │  └────────────────────────┘          │
 *  └──────────────────────────────────────┘
 */
class MessageBubble(
    private val project: Any? = null,
    role: Role,
    content: String = "",
    segments: List<ResponseSegment>? = null,
    /** 用户消息右上角删除按钮的回调（传 null 则不显示删除按钮） */
    private val onDelete: (() -> Unit)? = null,
    /** 代码文件标签列表（显示在用户消息中，带 ☕ 图标） */
    private val fileTabs: List<String> = emptyList()
) : JPanel(BorderLayout()) {

    /** 消息创建时间（HH:mm 格式） */
    val timestamp: String = formatTimestamp(System.currentTimeMillis())

    /** Streaming: 用于增量追加 token 的文本区域 */
    val streamTextArea: JBTextArea? = if (role == Role.STREAMING) JBTextArea() else null

    /** Streaming: 包裹文本区域的滚动面板 */
    val streamScrollPane: JBScrollPane? =
        if (role == Role.STREAMING) JBScrollPane() else null

    enum class Role {
        USER,
        ASSISTANT,
        STREAMING
    }

    init {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false

        when (role) {
            Role.USER -> setupUserMessage(content)
            Role.ASSISTANT -> setupAssistantMessage(content, segments)
            Role.STREAMING -> setupStreamingArea()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  用户消息 — 右对齐卡片：头像 + 时间 + 删除 + 文件标签 + 分割线 + 正文
    // ════════════════════════════════════════════════════════════════

    /** 紫色圆形 + toolwindow.svg 居中绘制（按比例缩放） */
    private fun createUserAvatar(): JPanel {
        val icon = IconLoader.getIcon("/icons/toolwindow.svg", MessageBubble::class.java)
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val size = minOf(width, height)
                // 紫色圆形背景
                g2.color = JBColor(0x7C3AED, 0x7C3AED)
                g2.fillOval(0, 0, size, size)
                // 按比例缩放 SVG 图标到圆形内（留 4px 边距保证完全展示）
                val padding = 4
                val targetSize = size - padding * 2
                val scale = targetSize.toDouble() / icon.iconWidth
                val scaledW = (icon.iconWidth * scale).toInt()
                val scaledH = (icon.iconHeight * scale).toInt()
                val ix = (width - scaledW) / 2
                val iy = (height - scaledH) / 2
                g2.translate(ix, iy)
                g2.scale(scale, scale)
                icon.paintIcon(this, g2, 0, 0)
                g2.dispose()
            }
        }.apply {
            preferredSize = Dimension(30, 30)
            minimumSize = Dimension(30, 30)
            maximumSize = Dimension(30, 30)
            isOpaque = false
        }
    }

    private fun setupUserMessage(content: String) {
        // ── 头像 ──
        val avatar = createUserAvatar()

        // ── 时间戳 ──
        val timeLabel = JLabel(timestamp).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x666666, 0x888888)
        }

        // ── 删除按钮（复用清空按钮图标 AllIcons.Actions.Close） ──
        val deleteBtn = JLabel(AllIcons.Actions.Close).apply {
            toolTipText = "删除此消息"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onDelete?.invoke()
                }
            })
        }
        if (onDelete == null) deleteBtn.isVisible = false

        // ── 头部行：左(头像 + me + 时间) | 右(删除) ──
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(avatar)
            val nameLabel = JLabel("me").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor(0x888888, 0xAAAAAA)
            }
            add(nameLabel)
            add(timeLabel)
        }
        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerLeft, BorderLayout.WEST)
            add(deleteBtn, BorderLayout.EAST)
        }

        // ── 文件标签行 ──
        val fileTabRow = if (fileTabs.isNotEmpty()) {
            val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false
                border = EmptyBorder(0, 0, 0, 0)
            }
            for (tab in fileTabs) {
                val tabLabel = JLabel("\u2615 " + tab).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor(0x666666, 0x999999)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(JBColor(0xE0E0E0, 0x444444), 1),
                        EmptyBorder(2, 8, 2, 8)
                    )
                }
                panel.add(tabLabel)
            }
            panel
        } else null

        // ── 分割线 ──
        val divider = JPanel().apply {
            preferredSize = Dimension(1, 1)
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            background = JBColor(0xE8E8E8, 0x3A3A3A)
            isOpaque = true
        }

        // ── 正文 ──
        val textArea = JTextArea(content).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(Font.PLAIN, 13f)
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            background = Color(0, 0, 0, 0)
            highlighter = null
            caretColor = Color(0, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(0, 0, 0, 0)
        }

        // ── 组装内层布局 ──
        val innerContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(headerRow)
            add(Box.createVerticalStrut(10))
            if (fileTabRow != null) {
                add(fileTabRow)
                add(Box.createVerticalStrut(10))
            }
            add(divider)
            add(Box.createVerticalStrut(10))
            add(textArea)
        }

        // ── 外层卡片（大圆角绘制） ──
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = 18
                g2.color = JBColor(0xF8F9FA, 0x24242A)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor(0xE8EAED, 0x3A3A3A)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            add(innerContent, BorderLayout.CENTER)
        }

        // ── 右对齐 ──
        val wrapper = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val c = GridBagConstraints()
            c.fill = GridBagConstraints.HORIZONTAL
            c.weightx = 1.0
            c.anchor = GridBagConstraints.EAST
            add(card, c)
        }

        add(wrapper, BorderLayout.CENTER)
    }

    // ════════════════════════════════════════════════════════════════
    //  AI 回复 — 左对齐卡片 + 环形图标 + 名称 + 内容块标记
    //  每个内容块（文字/代码/表格）前显示对应的彩色类型徽章
    // ════════════════════════════════════════════════════════════════

    private fun setupAssistantMessage(
        content: String,
        segments: List<ResponseSegment>?
    ) {
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = 18
                g2.color = JBColor(0xF8F9FA, 0x24242A)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor(0xE8EAED, 0x3A3A3A)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
        }

        val header = createModernHeader()

        val contentBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val resolvedSegments = segments ?: parseResponse(content)
        for (i in resolvedSegments.indices) {
            val seg = resolvedSegments[i]
            val isFirst = i == 0
            when (seg) {
                is ResponseSegment.Text -> {
                    if (!isFirst) {
                        contentBody.add(Box.createVerticalStrut(8))
                    }
                    val mdPane = MarkdownRenderer.createPane(
                        markdownText = seg.content,
                        fontSize = 13,
                        fgColor = JBColor(0x1A1A1A, 0xE0E0E0),
                        bgColor = null
                    )
                    contentBody.add(mdPane)
                }
                is ResponseSegment.Code -> {
                    if (!isFirst) {
                        contentBody.add(Box.createVerticalStrut(10))
                    }
                    val codeCard = CodeBlockCard(
                        project = project as? com.intellij.openapi.project.Project,
                        code = seg.content,
                        language = seg.language,
                        showInsertButton = true
                    )
                    contentBody.add(codeCard)
                }
                is ResponseSegment.Table -> {
                    if (!isFirst) {
                        contentBody.add(Box.createVerticalStrut(8))
                    }
                    val tablePanel = MessageTable(seg.headers, seg.rows)
                    contentBody.add(tablePanel)
                }
            }
        }

        if (contentBody.componentCount == 0) {
            contentBody.add(Box.createVerticalStrut(2))
        }

        val innerContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(contentBody, BorderLayout.CENTER)
        }

        card.add(innerContent, BorderLayout.CENTER)

        val outer = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val c = GridBagConstraints()
            c.fill = GridBagConstraints.HORIZONTAL
            c.weightx = 1.0
            c.anchor = GridBagConstraints.WEST
            add(card, c)
        }

        add(outer, BorderLayout.CENTER)
    }
    /** 加载 action.svg 作为 AI 品牌图标（按比例缩放） */
    private fun createRingIcon(): JPanel {
        val icon = IconLoader.getIcon("/icons/action.svg", MessageBubble::class.java)
        return object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val size = minOf(width, height)
                g2.color = JBColor(0x7C3AED, 0x7C3AED)
                g2.fillOval(0, 0, size, size)
                // 按比例缩放 SVG 图标到圆形内（留 3px 边距保证完全展示）
                val padding = 3
                val targetSize = size - padding * 2
                val scale = targetSize.toDouble() / icon.iconWidth
                val scaledW = (icon.iconWidth * scale).toInt()
                val scaledH = (icon.iconHeight * scale).toInt()
                val ix = (width - scaledW) / 2
                val iy = (height - scaledH) / 2
                g2.translate(ix, iy)
                g2.scale(scale, scale)
                icon.paintIcon(this, g2, 0, 0)
                g2.dispose()
            }
        }.apply {
            preferredSize = Dimension(26, 26)
            minimumSize = Dimension(26, 26)
            maximumSize = Dimension(26, 26)
            isOpaque = false
        }
    }

    /**
     * 创建 AI 消息头部 — 环形图标 + "DP Helper"
     */
    private fun createModernHeader(): JPanel {
        val ringIcon = createRingIcon()

        val nameLabel = JLabel("DP Helper").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x888888, 0xAAAAAA)
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 8, 0)
            add(ringIcon)
            add(nameLabel)
        }

        return header
    }

    // ════════════════════════════════════════════════════════════════
    //  流式响应区域 — 轻量思考动画
    // ════════════════════════════════════════════════════════════════

    private fun setupStreamingArea() {
        val card = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = 18
                g2.color = JBColor(0xF8F9FA, 0x24242A)
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = JBColor(0xE8EAED, 0x3A3A3A)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
        }

        val ringIcon = createRingIcon()
        val nameLabel = JLabel("DP Helper").apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x888888, 0xAAAAAA)
        }
        val thinkingLabel = JLabel("思考中...").apply {
            font = font.deriveFont(Font.ITALIC, 10f)
            foreground = JBColor(0x999999, 0x888888)
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(ringIcon)
            add(nameLabel)
            add(thinkingLabel)
        }

        streamTextArea!!.apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(Font.PLAIN, 13f)
            background = Color(0, 0, 0, 0)
            margin = JBUI.insets(0, 0, 0, 0)
            border = EmptyBorder(0, 0, 0, 0)
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            (caret as DefaultCaret).updatePolicy = DefaultCaret.ALWAYS_UPDATE
            text = "... 思考中 \u25D0"
        }

        streamScrollPane!!.apply {
            border = EmptyBorder(0, 0, 0, 0)
            isOpaque = false
            viewport.isOpaque = false
            setViewportView(streamTextArea)
            preferredSize = Dimension(100, 60)
        }

        val innerContent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(streamScrollPane, BorderLayout.CENTER)
        }

        card.add(innerContent, BorderLayout.CENTER)

        val outer = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val c = GridBagConstraints()
            c.fill = GridBagConstraints.HORIZONTAL
            c.weightx = 1.0
            c.anchor = GridBagConstraints.WEST
            add(card, c)
        }

        add(outer, BorderLayout.CENTER)
    }

    companion object {
        /** 格式化时间戳为 HH:mm */
        private fun formatTimestamp(millis: Long): String {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return fmt.format(java.util.Date(millis))
        }
    }
}
