package com.deepseek.plugin.ui

import com.deepseek.plugin.ui.CodeBlockCard.Companion.parseResponse
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ActionListener
import kotlin.math.ceil
import java.awt.FontMetrics
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
 * 独立自绘单位 — 每个 MessageBubble 自行绘制圆角背景，
 * 不依赖父容器背景板，避免一个气泡变化时影响其他气泡显示。
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
    val role: Role,
    content: String = "",
    segments: List<ResponseSegment>? = null,
    /** 用户消息右上角删除按钮的回调（传 null 则不显示删除按钮） */
    private val onDelete: (() -> Unit)? = null,
    /** 代码文件标签列表（显示在用户消息中，带 ☕ 图标） */
    private val fileTabs: List<String> = emptyList(),
    /** 模型的深度思考过程（reasoning_content），仅 ASSISTANT 角色使用 */
    private val reasoning: String? = null
) : JPanel(GridBagLayout()) {

    /** 消息创建时间（HH:mm 格式） */
    val timestamp: String = formatTimestamp(System.currentTimeMillis())

    /** Streaming: 用于增量追加 token 的文本区域（无滚动面板，随内容自然撑开）
     *  重写 getPreferredSize() 以正确反映 lineWrap 后的视觉行数，
     *  避免一长段文本(无换行符)只算1行导致气泡被压扁 */
    val streamTextArea: JBTextArea? = if (role == Role.STREAMING)
        object : JBTextArea() {
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                if (!lineWrap || width <= 0) return size
                val ins = insets
                val lineH = getRowHeight()
                val avail = width - ins.left - ins.right
                if (avail <= 0 || lineH <= 0) return size
                val fm = getFontMetrics(font)
                val txt = text ?: ""
                val logicalLines = txt.split("\n")
                var totalH = 0
                for (line in logicalLines) {
                    if (line.isEmpty()) {
                        totalH += lineH
                    } else {
                        val lineW = fm.stringWidth(line)
                        val wrappedLines = ceil(lineW.toDouble() / avail).toInt()
                        totalH += lineH * maxOf(1, wrappedLines)
                    }
                }
                return Dimension(size.width, totalH + ins.top + ins.bottom)
            }
        }
    else null

    enum class Role {
        USER,
        ASSISTANT,
        STREAMING
    }

    /** 当前气泡画圆角背景的锚点方向（USER=右对齐→左边空，ASSISTANT=左对齐→右边空） */
    private val anchor: Int
        get() = if (role == Role.USER) GridBagConstraints.EAST else GridBagConstraints.WEST

    // ── 可视化内容面板（即圆角矩形内的全部内容）──
    private lateinit var contentPanel: JPanel

    /** 思考过程面板的展开/收起状态 */
    private var reasoningExpanded = false

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        when (role) {
            Role.USER -> setupUserMessage(content)
            Role.ASSISTANT -> setupAssistantMessage(content, segments, reasoning)
            Role.STREAMING -> setupStreamingArea()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  自绘圆角背景 — 仅绘制在 contentPanel 区域，不占用对齐留白
    // ════════════════════════════════════════════════════════════════

    override fun paintComponent(g: Graphics) {
        if (!::contentPanel.isInitialized) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = 18
        val b = contentPanel.bounds
        g2.color = JBColor(0xF8F9FA, 0x24242A)
        g2.fillRoundRect(b.x, b.y, b.width, b.height, arc, arc)
        g2.color = JBColor(0xE8EAED, 0x3A3A3A)
        g2.drawRoundRect(b.x, b.y, b.width, b.height, arc, arc)
        g2.dispose()
    }

    /**
     * 将 [contentPanel] 添加到本组件（GridBagLayout）并设置锚点对齐。
     * 由各 setupXxxMessage 方法在构建完 contentPanel 后调用。
     */
    private fun attachContent() {
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            anchor = this@MessageBubble.anchor
        }
        add(contentPanel, c)
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
                g2.color = JBColor(0x7C3AED, 0x7C3AED)
                g2.fillOval(0, 0, size, size)
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

        // ── 删除按钮（InplaceButton：原生 hover 高亮 + 点击反馈）
        val deleteBtn = InplaceButton(
            I18n.tr("bubble.delete"),
            AllIcons.Actions.Close,
            ActionListener { onDelete?.invoke() }
        ).apply {
            isVisible = onDelete != null
        }

        // ── 头部行 ──
        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(avatar)
            val nameLabel = JLabel(I18n.tr("bubble.me")).apply {
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
            font = font.deriveFont(Font.PLAIN, DeepSeekSettings.instance.contentFontSize.toFloat())
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            background = Color(0, 0, 0, 0)
            highlighter = null
            caretColor = Color(0, 0, 0, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(0, 0, 0, 0)
        }

        // ── 组装内容面板 ──
        contentPanel = JPanel().apply {
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

        attachContent()
    }

    // ════════════════════════════════════════════════════════════════
    //  AI 回复 — 左对齐卡片 + 环形图标 + 名称 + 内容块
    // ════════════════════════════════════════════════════════════════

    private fun setupAssistantMessage(
        content: String,
        segments: List<ResponseSegment>?,
        reasoning: String? = null
    ) {
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
                    if (!isFirst) contentBody.add(Box.createVerticalStrut(8))
                    val contentFontSize = DeepSeekSettings.instance.contentFontSize
                    val mdPane = MarkdownRenderer.createPane(
                        markdownText = seg.content,
                        fontSize = contentFontSize,
                        fgColor = JBColor(0x1A1A1A, 0xE0E0E0),
                        bgColor = null
                    )
                    contentBody.add(mdPane)
                }
                is ResponseSegment.Code -> {
                    if (!isFirst) contentBody.add(Box.createVerticalStrut(10))
                    val codeCard = CodeBlockCard(
                        project = project as? com.intellij.openapi.project.Project,
                        code = seg.content,
                        language = seg.language,
                        showInsertButton = true
                    )
                    contentBody.add(codeCard)
                }
                is ResponseSegment.Table -> {
                    if (!isFirst) contentBody.add(Box.createVerticalStrut(8))
                    val tablePanel = MessageTable(seg.headers, seg.rows)
                    contentBody.add(tablePanel)
                }
            }
        }

        if (contentBody.componentCount == 0) {
            contentBody.add(Box.createVerticalStrut(2))
        }

        // ── 中心面板：思考过程(可选) + 最终回答 ──
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            if (reasoning != null && reasoning.isNotBlank()) {
                add(createReasoningPanel(reasoning))
                add(Box.createVerticalStrut(12))
            }
            add(contentBody)
        }

        contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
        }

        attachContent()
    }

    // ════════════════════════════════════════════════════════════════
    //  可折叠「思考过程」面板
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建可折叠的思考过程面板，位于 AI 回复头部与正文之间。
     * - 折叠时：显示 "🧠 思考过程  ▸  共 xxx 字"
     * - 展开时：显示思考内容（灰色底色 + 灰色文字），可再次折叠
     */
    private fun createReasoningPanel(reasoningText: String): JPanel {
        val reasoningBody = JPanel(BorderLayout())
        reasoningBody.isOpaque = false

        // ── 内容区域（初始隐藏）──
        val contentArea = JBTextArea(reasoningText.trim()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("SansSerif", (DeepSeekSettings.instance.contentFontSize - 1).coerceAtLeast(10))
            margin = JBUI.insets(8, 10, 8, 10)
            border = JBUI.Borders.customLine(JBColor(0xD0D0D0, 0x444444), 1)
            background = JBColor(0xF0F0F0, 0x2A2A2A)
            foreground = JBColor(0x666666, 0x999999)
            isOpaque = true
            isVisible = false
        }

        // ── 头部行：▶ 思考过程  共 xxx 字 ──
        val toggleArrow = JLabel("\u25B6") // ▶ (collapsed)
        toggleArrow.font = toggleArrow.font.deriveFont(10f)

        val titleLabel = JLabel(I18n.tr("bubble.reasoning")).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x888888, 0xAAAAAA)
        }

        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(toggleArrow)
            add(titleLabel)
        }

        reasoningBody.add(headerPanel, BorderLayout.NORTH)

        // ── 点击切换展开/收起 ──
        reasoningExpanded = false
        val toggleListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                reasoningExpanded = !reasoningExpanded
                contentArea.isVisible = reasoningExpanded
                toggleArrow.text = if (reasoningExpanded) "\u25BC" else "\u25B6" // ▼ / ▶
                // 触发父容器重新布局
                reasoningBody.revalidate()
                var parent = reasoningBody.parent
                while (parent != null) {
                    parent.revalidate()
                    parent = parent.parent
                }
            }
        }
        headerPanel.addMouseListener(toggleListener)

        reasoningBody.add(contentArea, BorderLayout.CENTER)

        // 整体包一层，保证 BoxLayout 中纵向布局正确
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt())
            add(reasoningBody, BorderLayout.NORTH)
        }
        return wrapper
    }

    // ════════════════════════════════════════════════════════════════
    //  AI 品牌环形图标
    // ════════════════════════════════════════════════════════════════

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

    private fun createModernHeader(): JPanel {
        val ringIcon = createRingIcon()
        val nameLabel = JLabel(I18n.tr("bubble.dp.helper")).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x888888, 0xAAAAAA)
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            border = EmptyBorder(0, 0, 8, 0)
            add(ringIcon)
            add(nameLabel)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  流式响应区域 — 轻量思考动画
    // ════════════════════════════════════════════════════════════════

    private fun setupStreamingArea() {
        val ringIcon = createRingIcon()
        val nameLabel = JLabel(I18n.tr("bubble.dp.helper")).apply {
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor(0x888888, 0xAAAAAA)
        }
        val thinkingLabel = JLabel(I18n.tr("bubble.thinking")).apply {
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
            font = font.deriveFont(Font.PLAIN, DeepSeekSettings.instance.contentFontSize.toFloat())
            background = Color(0, 0, 0, 0)
            margin = JBUI.insets(0, 0, 0, 0)
            border = EmptyBorder(0, 0, 0, 0)
            foreground = JBColor(0x1A1A1A, 0xE0E0E0)
            (caret as DefaultCaret).updatePolicy = DefaultCaret.ALWAYS_UPDATE
            text = "... 思考中 \u25D0"
        }

        contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(12, 16, 16, 16)
            add(header, BorderLayout.NORTH)
            add(streamTextArea!!, BorderLayout.CENTER)
        }

        attachContent()
    }

    companion object {
        /** 格式化时间戳为 HH:mm */
        private fun formatTimestamp(millis: Long): String {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            return fmt.format(java.util.Date(millis))
        }
    }
}
