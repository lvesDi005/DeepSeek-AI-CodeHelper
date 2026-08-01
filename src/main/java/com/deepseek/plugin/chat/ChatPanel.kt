package com.deepseek.plugin.chat



import com.deepseek.plugin.ui.PluginTheme
import com.deepseek.plugin.api.ChatMessage

import com.deepseek.plugin.api.ChatSession

import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT

import com.deepseek.plugin.api.DeepSeekApiClient

import com.deepseek.plugin.api.DeepSeekPluginException

import com.deepseek.plugin.api.LlmProviderRegistry

import com.deepseek.plugin.api.StepFunApiClient

import com.deepseek.plugin.api.Usage

import com.deepseek.plugin.chat.ChatState

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.i18n.ContentFontChangeListener
import com.deepseek.plugin.i18n.I18nTopics
import com.deepseek.plugin.i18n.LanguageChangeListener
import com.deepseek.plugin.i18n.ThemeChangeListener

import com.deepseek.plugin.context.ProjectContextProvider

import com.deepseek.plugin.context.RagRetriever

import com.deepseek.plugin.context.SearchCoordinator

import com.deepseek.plugin.search.AgenticSearch

import com.deepseek.plugin.access.ChainedFileAccess
import com.deepseek.plugin.access.FileAccessService

import com.deepseek.plugin.mcp.client.ExternalMcpManager

import com.deepseek.plugin.search.ToolUseEngine

import com.deepseek.plugin.store.SessionStore

import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.settings.toSnapshot

import com.deepseek.plugin.ui.AttachedFile

import com.deepseek.plugin.ui.ChangeManagementPanel

import com.deepseek.plugin.ui.ChatInputBar

import com.deepseek.plugin.ui.ChatToolbar

import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.MarkdownRenderer

import com.deepseek.plugin.ui.FileAttachmentPreview

import com.deepseek.plugin.ui.HistoryDialog

import com.deepseek.plugin.ui.MessageBubble

import com.deepseek.plugin.ui.ResponseSegment

import com.deepseek.plugin.ui.SelectedCodePreview

import com.deepseek.plugin.ui.SessionBar

import com.deepseek.plugin.ui.MarqueeLabel

import com.deepseek.plugin.ui.UnifiedSettingsPanel

import com.deepseek.plugin.ui.TranslateDialog

import com.deepseek.plugin.ui.WelcomePanel

import com.intellij.icons.AllIcons

import com.intellij.ide.BrowserUtil

import com.intellij.openapi.Disposable

import com.intellij.openapi.ui.MessageType

import com.intellij.openapi.wm.ToolWindowManager

import com.intellij.openapi.application.ApplicationManager

import com.intellij.openapi.command.WriteCommandAction

import com.intellij.openapi.editor.EditorFactory

import com.intellij.openapi.editor.event.EditorFactoryEvent

import com.intellij.openapi.editor.event.EditorFactoryListener

import com.intellij.openapi.editor.event.SelectionEvent

import com.intellij.openapi.editor.event.SelectionListener

import com.intellij.openapi.fileEditor.FileEditorManager

import com.intellij.openapi.progress.ProgressManager

import com.intellij.openapi.project.Project

import com.intellij.openapi.roots.ProjectRootManager

import com.intellij.openapi.vfs.VirtualFile

import com.intellij.ui.JBColor

import com.intellij.ui.components.*

import com.intellij.util.ui.JBUI

import kotlin.math.ceil

import okhttp3.sse.EventSource

import java.awt.BasicStroke

import java.awt.BorderLayout

import java.awt.CardLayout

import java.awt.Color

import java.awt.Component

import java.awt.Cursor

import java.awt.Dimension

import java.awt.FlowLayout

import java.awt.Font

import java.awt.Graphics

import java.awt.Graphics2D

import java.awt.RenderingHints

import java.awt.GridBagConstraints

import java.awt.GridBagLayout

import java.awt.event.MouseAdapter

import java.awt.event.MouseEvent

import java.util.concurrent.atomic.AtomicReference

import javax.swing.*

import javax.swing.text.DefaultCaret

import javax.swing.text.DefaultHighlighter

import javax.swing.Timer



enum class ChatMode {

    Q_A, Q_A_SCAN, AGENT

}



class ChatPanel(private val project: Project) : JPanel(CardLayout()), Disposable {



    companion object {

        // Context search limits

        private const val MAX_SEARCH_KEYWORDS = 5

        private const val MAX_FILES_PER_KEYWORD = 3

        private const val MAX_FILE_SIZE = 15000

        private const val MAX_CONTEXT_LINES = 300

        // Phase 0 retry

        private const val MAX_PHASE0_RETRIES = 1

        /** 当前活动的 ChatPanel 实例，供外部 Action 向聊天面板推送内容 */

        @JvmStatic

        var currentInstance: ChatPanel? = null

            private set

    }



    internal val client = DeepSeekApiClient()

    private val stepFunClient = StepFunApiClient()

    private val contextProvider = ProjectContextProvider(project)

    private val ragRetriever = RagRetriever(project)

    private val searchCoordinator = SearchCoordinator(
        project,
        externalToolDefinitions = ExternalMcpManager.getInstance().getToolDefinitionsForLlm(),
        externalToolExecutor = { name, params ->
            val tool = ExternalMcpManager.getInstance().findTool(name)
            if (tool != null) {
                val result = tool.execute(params)
                if (result.isError) "[错误: ${result.content.firstOrNull()?.let { it } }]"
                else result.content.firstOrNull()?.let { (it as? com.deepseek.plugin.mcp.protocol.McpContent.Text)?.text }
            } else null
        }
    )

    private val agenticSearch = AgenticSearch(project)

    /** Unified file access for scanning/searching, with automatic fallback. */
    private val fileAccess: FileAccessService = ChainedFileAccess()

    private val sessionStore = SessionStore(project.basePath)

    private val sessions = mutableListOf<ChatSession>()

    private var currentSessionIndex = 0

    /** 线程安全的聊天状态机 — 替代 isStreaming + currentEventSource + streamBuffer + streamingBubble + … */

    internal val chatState = AtomicReference<ChatState>(ChatState.Idle)

    private var sessionCounter = 1

    private var currentMode = ChatMode.Q_A_SCAN



    /** 意图确认阶段的回调 — 非 null 时正在等待用户补充说明 */

    internal var pendingConfirmation: ((clarification: String) -> Unit)? = null



    /** 统一设置页面 — 覆盖整个插件区域，含顶部图标导航栏 */

    internal val unifiedSettingsPanel: UnifiedSettingsPanel



    /** 变更管理面板 — 覆盖整个插件区域 */

    private val changeManagementPanel: ChangeManagementPanel



    internal val fileOperationExecutor = FileOperationExecutor(

        project = project,

        addMessageLabel = { addMessageLabel(it) },

        getSourceRootPaths = { getSourceRootPaths() }

    )



    private var pipelineTotalTokens: Int = 0

    private var phase0RetryCount: Int = 0



    /** 输入区默认/最小/最大高度 */

    private val defaultInputHeight = 160

    private val minInputHeight = 140

    private val maxInputHeight = 600



    /** P3: 虚拟化滚动—当前会话中可见的第一条消息索引（从尾部算） */

    private var visibleStartIndex = 0

    /** P3: 虚拟化滚动—每个批次渲染的最大消息数 */

    private val VISIBLE_BATCH_SIZE = 30



    /** 防抖保存定时器：500ms 内多次调用只触发一次磁盘写入 */

    private val saveTimer = Timer(500) {

        SwingUtilities.invokeLater { doSaveSessions() }

    }.apply { isRepeats = false }



    // ── 思考中动画 ──

    private val spinnerChars = listOf("◐", "◓", "◑", "◒")

    private var thinkingTimer: Timer? = null

    private var spinnerIndex = 0



    // ── Selected code preview state ──

    internal data class SelectedContext(

        val fileName: String,

        val startLine: Int,

        val endLine: Int,

        val snippet: String

    )



    private var selectedContext: SelectedContext? = null

    private val selectedCodePreviewPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {

        isVisible = false

    }



    // ── Message tracking for anchor dots ──

    private data class MessageEntry(

        val panel: JPanel,                  // the user message panel

        val text: String,

        var responsePanel: JPanel? = null   // linked AI response, filled later

    )

    private val userMessages = mutableListOf<MessageEntry>()



    // ── File attachment state ──

    private val attachedFiles = mutableListOf<AttachedFile>()

    private val fileAttachmentPanel = JPanel(BorderLayout()).apply {

        isVisible = false

    }



    // ── Streaming state (迁移到 ChatState 状态机) ──

    /** 用于 EDT 上判断是否正在流式 — 由 chatState 驱动 */

    private val isStreaming: Boolean get() = chatState.get() is ChatState.Streaming



    // ── UI Components ──



    private val sessionComboBox = JComboBox<String>()



    /** Vertical panel that holds all rendered messages. */

    internal val messagesPanel = JPanel().apply {

        layout = GridBagLayout()

        // 底部留 28px 空间，避免滚动到底时最后一条消息被截断

        border = JBUI.Borders.empty(0, 0, 28, 0)

    }



    /** Transparent filler that expands to fill empty vertical space below messages. */

    private val verticalFiller = JPanel().apply { isOpaque = false }



    /** GridBagConstraints for a message row — fills cell completely, no vertical gaps. */

    private val fillWidthConstraints = GridBagConstraints().apply {

        fill = GridBagConstraints.BOTH

        weightx = 1.0

        gridwidth = GridBagConstraints.REMAINDER

    }



    private val messagesScrollPane = JBScrollPane(messagesPanel).apply {

        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS

        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        border = JBUI.Borders.empty()

    }.also { pane ->

        // ── 自动滚动跟踪：用户向上滚动时暂停跟随生成，滚动到底部时恢复 ──

        pane.verticalScrollBar.addAdjustmentListener {

            val vsb = pane.verticalScrollBar

            autoScrollToBottom = vsb.value + vsb.visibleAmount >= vsb.maximum - 50

        }

    }



    /**

     * AI 流式生成时是否自动滚动到底部。

     * 用户向上滚动查看历史时置 false（暂停跟随），

     * 用户滚动到底部或发送新消息时置 true（恢复跟随）。

     */

    @Volatile

    private var autoScrollToBottom = true



    /** Empty-state welcome panel shown when there are no messages. */

    private val welcomePanel = WelcomePanel()



    /** CardLayout container that switches between [welcomePanel] and [messagesScrollPane]. */

    private val messageContainer = JPanel(CardLayout()).apply {

        isOpaque = false

        add(welcomePanel, "welcome")

        add(messagesScrollPane, "messages")

    }



    /** Show the empty-state welcome panel and hide messages. */

    private fun showWelcome() {

        (messageContainer.layout as CardLayout).show(messageContainer, "welcome")

        welcomePanel.revalidate()

        welcomePanel.repaint()

    }



    /** Show the messages panel and hide the welcome background. */

    private fun showMessages() {

        (messageContainer.layout as CardLayout).show(messageContainer, "messages")

        messagesScrollPane.revalidate()

        messagesScrollPane.repaint()

    }



    /**
     * 语言切换即时刷新：更新发送/停止按钮、欢迎面板等静态文案。
     * 由 [I18nTopics.LANGUAGE_CHANGED] 事件触发。
     */
    private fun refreshI18nTexts() {
        try {
            sendStopButton.text = if (isStreaming) I18n.tr("chat.stop.enter") else I18n.tr("chat.send.enter")
            sendStopButton.toolTipText = if (isStreaming) I18n.tr("chat.tooltip.stop") else I18n.tr("chat.tooltip.send")
            welcomePanel.refreshTexts()
            refreshTooltips()
        } catch (_: Exception) {
        }
    }

    /**
     * 语言切换时遍历组件树，刷新所有通过 [I18n.tooltip] 标记过的悬浮提示文字。
     */
    private fun refreshTooltips() {
        I18n.refreshTooltips(this)
    }

    /**
     * 字体切换即时刷新：遍历消息面板，更新所有已渲染消息正文的字体大小。
     * 由 [I18nTopics.CONTENT_FONT_CHANGED] 事件触发。
     */
    private fun refreshAllMessageFonts() {
        val size = DeepSeekSettings.instance.contentFontSize
        fun walk(c: java.awt.Component) {
            when (c) {
                is javax.swing.JTextArea -> if (!c.isEditable && !c.isFocusable) {
                    c.font = c.font.deriveFont(Font.PLAIN, size.toFloat())
                }
                is javax.swing.JEditorPane -> if (c.contentType.startsWith("text/html")) {
                    // 需重建 StyleSheet（CSS font-size 优先于 pane.font），否则已生成内容不变化
                    MarkdownRenderer.refreshFont(c, size)
                }
            }
            if (c is java.awt.Container) {
                for (i in 0 until c.componentCount) walk(c.getComponent(i))
            }
        }
        walk(messagesPanel)
        messagesPanel.revalidate()
        messagesPanel.repaint()
    }

    /**
     * 插件主题切换即时刷新：重新渲染当前会话消息以应用新配色。
     * 由 [I18nTopics.THEME_CHANGED] 事件触发。
     */
    private fun refreshThemeColors() {
        try {
            // 更新消息面板背景（构建时固化，需随主题刷新）
            val msgBg = PluginTheme.color(0xFFFFFF, 0x2B2B2B)
            messagesScrollPane.background = msgBg
            messagesPanel.background = msgBg
            messagesScrollPane.viewport.background = msgBg
            messagesScrollPane.viewport.isOpaque = true

            val session = currentSession()
            messagesPanel.removeAll()
            userMessages.clear()
            if (session.messages.isEmpty()) {
                showWelcome()
                return
            }
            showMessages()
            ensureMessagesFiller()
            addMessageLabel("=== ${session.name} ===")
            val total = session.messages.size
            visibleStartIndex = maxOf(0, total - VISIBLE_BATCH_SIZE)
            renderMessageRange(session, visibleStartIndex, total)
            messagesPanel.revalidate()
            messagesPanel.repaint()
        } catch (_: Exception) {
        }
    }



    internal val inputArea = AutoResizingTextArea(4, 0, project, { sendMessage() }, { isStreaming },

        onImagePasted = { file -> addFileAttachment(file) }

    )



    // ── Combined send/stop button ──



    internal val sendStopButton = object : JButton(I18n.tr("chat.send.enter")) {

        override fun paintComponent(g: Graphics) {

            val g2 = g.create() as Graphics2D

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val isStop = text?.contains(I18n.tr("chat.stop.enter").take(2), ignoreCase = true) == true

            g2.color = if (model.isRollover) {

                if (isStop) JBColor(0xE57373, 0xEF5350) else JBColor(0xE0E0E0, 0x4A4A4A)

            } else {

                if (isStop) JBColor(0xEF9A9A, 0xC62828) else Color(0, 0, 0, 0)

            }

            g2.fillRoundRect(0, 0, width, height, 8, 8)

            // 圆角边框：发送态浅绿、停止态浅红

            g2.color = if (isStop) JBColor(0xEF9A9A, 0xE57373) else JBColor(0xA5D6A7, 0x66BB6A)

            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

            super.paintComponent(g)

            g2.dispose()

        }



        override fun setText(text: String?) {

            super.setText(text)

            // 根据文本自动切换图标

            val isStop = text?.contains(I18n.tr("chat.stop.enter").take(2), ignoreCase = true) == true

            icon = if (isStop) AllIcons.Actions.Suspend else AllIcons.Actions.Execute

        }

    }.apply {

        toolTipText = I18n.tr("chat.tooltip.send")

        addActionListener { if (isStreaming) stopStreaming() else sendMessage() }

        isOpaque = false

        isContentAreaFilled = false

        isBorderPainted = false

        isFocusPainted = false

        font = font.deriveFont(Font.BOLD, 12f)

    }



    internal val messageHistory: MutableList<ChatMessage>

        get() = currentSession().messages



    init {

        // ── 订阅语言/字体/主题切换事件，实现即时刷新 ──
        project.messageBus.connect(this).apply {
            subscribe(I18nTopics.LANGUAGE_CHANGED, object : LanguageChangeListener {
                override fun languageChanged() {
                    refreshI18nTexts()
                }
            })
            subscribe(I18nTopics.CONTENT_FONT_CHANGED, object : ContentFontChangeListener {
                override fun fontChanged() {
                    refreshAllMessageFonts()
                }
            })
            subscribe(I18nTopics.THEME_CHANGED, object : ThemeChangeListener {
                override fun themeChanged() {
                    refreshThemeColors()
                }
            })
        }

        // ── load saved sessions ──

        val saved = sessionStore.load()

        if (saved != null) {

            sessions.addAll(saved.first)

            sessionCounter = saved.second

            for (s in sessions) {

                sessionComboBox.addItem(s.name)

            }

        } else {

            sessions.add(ChatSession(I18n.tr("chat.session.default.name")))

            sessionComboBox.addItem(I18n.tr("chat.session.default.name"))

        }

        sessionComboBox.addActionListener {

            val idx = sessionComboBox.selectedIndex

            if (idx >= 0 && idx < sessions.size && idx != currentSessionIndex) {

                switchToSession(idx)

            }

        }



        // ── 合并工具栏 + 会话栏为单行 ──

        // 左侧：[▼会话1] [+] [🗑清除当前]

        // 右侧：[≡历史] [🕐变更管理] [⚙设置]

        val topBar = JPanel(BorderLayout()).apply {

            background = JBColor(Color(0xE8E8E8), Color(0x333333))

            isOpaque = true

            add(SessionBar(

                sessionComboBox = sessionComboBox,

                onNewSession = { createNewSession() },

                onClearCurrent = {

                    currentSession().messages.clear()

                    currentSession().totalTokens = 0

                    messagesPanel.removeAll()

                    messagesPanel.revalidate()

                    messagesPanel.repaint()

                    userMessages.clear()

                    showWelcome()

                    saveSessions()

                }

            ), BorderLayout.WEST)

            add(ChatToolbar(

                onShowHistory = { showHistoryDialog() },

                onShowSettings = { showSkillSettings() },

                onShowChangeManagement = { showChangeManagement() }

            ), BorderLayout.EAST)

        }



        // Wrap messages container — anchor dots are embedded per-message

        val messagesWithNav = JPanel(BorderLayout()).apply {

            isOpaque = false

            add(messageContainer, BorderLayout.CENTER)

        }



        // 聊天区背景设为主题自适应

        messagesScrollPane.viewport.apply {

            isOpaque = true

            background = PluginTheme.color(0xFFFFFF, 0x2B2B2B)

        }

        messagesScrollPane.background = PluginTheme.color(0xFFFFFF, 0x2B2B2B)

        messagesPanel.background = PluginTheme.color(0xFFFFFF, 0x2B2B2B)

        messagesPanel.isOpaque = true



        // ── Chat input bar ──

        val inputScrollPane = JBScrollPane(inputArea).apply {

            isOpaque = false

            viewport.isOpaque = false

            border = JBUI.Borders.empty()

        }

        val chatInputBar = ChatInputBar(

            inputScrollPane = inputScrollPane,

            selectedCodePanel = selectedCodePreviewPanel,

            fileAttachmentPanel = fileAttachmentPanel,

            modeSelector = createModeDropdown(),

            settingsButton = createSettingsButton(),

            uploadButton = createUploadButton(),

            translateButton = createTranslateButton(),

            sendStopButton = sendStopButton

        )



        // ── Input area (just ChatInputBar; tabBarArea moved to NORTH) ──

        val inputAreaPanel = JPanel().apply {

            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            isOpaque = false

            preferredSize = Dimension(Short.MAX_VALUE.toInt(), defaultInputHeight)

            add(chatInputBar)

        }



        // 输入框拖拽手柄直接调整 inputAreaPanel 高度

        chatInputBar.onResizeRequest = { height ->

            if (height == Int.MAX_VALUE) {

                // 双击重置

                inputAreaPanel.preferredSize = Dimension(Short.MAX_VALUE.toInt(), defaultInputHeight)

            } else {

                val clamped = height.coerceIn(minInputHeight, maxInputHeight)

                inputAreaPanel.preferredSize = Dimension(Short.MAX_VALUE.toInt(), clamped)

            }

            inputAreaPanel.revalidate()

            this@ChatPanel.revalidate()

        }



        // ── Announcement banner (shown every time toolwindow opens) ──

        val announcementBanner = JPanel(BorderLayout()).also { banner ->

            banner.apply {

                isOpaque = true

                background = JBColor(0xE3F2FD, 0x1A3A5C) // light blue (light) / dark blue (dark)

                border = JBUI.Borders.empty(6, 12, 6, 4)

                val iconLabel = JLabel("\uD83D\uDCE2").apply {

                    font = font.deriveFont(14f)

                }

                val contentLabel = MarqueeLabel(I18n.tr("announcement.content")).apply {

                    font = font.deriveFont(Font.PLAIN, 12f)

                    foreground = JBColor(0x1565C0, 0x90CAF9)

                    addMouseListener(object : MouseAdapter() {

                        override fun mouseClicked(e: MouseEvent) {

                            BrowserUtil.browse(I18n.tr("announcement.content.link"))

                        }

                    })

                }

                val closeButton = JButton("\u2716").apply {

                    font = font.deriveFont(12f)

                    foreground = JBColor(0x000000, 0xAAAAAA)

                    isOpaque = false

                    isContentAreaFilled = false

                    isBorderPainted = false

                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                    addActionListener {

                        banner.isVisible = false

                    }

                }

                add(iconLabel, BorderLayout.WEST)

                add(contentLabel, BorderLayout.CENTER)

                add(closeButton, BorderLayout.EAST)



                // Add tooltip with dismiss hint

                I18n.tooltip(closeButton, "announcement.dismiss")

            }

        }



        // ── Wrap the entire chat view into one panel for CardLayout ──

        val chatView = JPanel(BorderLayout()).apply {

            isOpaque = true

            val northWrapper = JPanel().apply {

                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                isOpaque = false

                add(topBar)

                add(announcementBanner)

            }

            add(northWrapper, BorderLayout.NORTH)

            add(messagesWithNav, BorderLayout.CENTER)

            add(inputAreaPanel, BorderLayout.SOUTH)

        }



        add(chatView, "chat")



        // ── Unified settings panel (full-coverage overlay) ──

        unifiedSettingsPanel = UnifiedSettingsPanel(project, onClose = { showChatView() })

        add(unifiedSettingsPanel, "settings")



        // ── Change Management panel (full-coverage overlay) ──

        changeManagementPanel = ChangeManagementPanel(project, onClose = { showChatView() })

        add(changeManagementPanel, "changeManagement")



        // Show chat view by default

        showChatView()



        // ── Width listener (removed — anchor dots embedded per-message) ──



        // If we have saved sessions, render the most recently active one

        if (saved != null && sessions.isNotEmpty()) {

            // Find the most recent session (last used)

            val lastActiveIdx = sessions.indices.maxByOrNull { sessions[it].lastActiveTime } ?: 0

            switchToSession(lastActiveIdx)

        } else {

            // Show the empty-state welcome panel

            showWelcome()

        }

        setupSelectionListener()



        // 注册为当前活动实例，供 UploadConsoleAction 等外部调用

        currentInstance = this



        // ── Scroll listener (removed — anchor dots embedded per-message) ──

    }



    // ===== Selection → input =====



    private fun setupSelectionListener() {

        val selListener = object : SelectionListener {

            override fun selectionChanged(e: SelectionEvent) {

                ApplicationManager.getApplication().invokeLater { fillInputFromSelection() }

            }

        }

        for (editor in EditorFactory.getInstance().allEditors) {

            editor.selectionModel.addSelectionListener(selListener)

        }

        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {

            override fun editorCreated(event: EditorFactoryEvent) {

                event.editor.selectionModel.addSelectionListener(selListener)

            }

            override fun editorReleased(event: EditorFactoryEvent) {

                event.editor.selectionModel.removeSelectionListener(selListener)

            }

        }, project)

    }



    private fun fillInputFromSelection() {

        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return

        val sm = editor.selectionModel

        if (!sm.hasSelection()) return



        val code = sm.selectedText ?: return

        if (code.isBlank()) return



        val doc = editor.document

        val startLine = doc.getLineNumber(sm.selectionStart) + 1

        val endLine = doc.getLineNumber(sm.selectionEnd) + 1



        val file = editor.virtualFile?.name ?: "unknown"



        // Set the selection context and show the preview card

        setSelectedContext(SelectedContext(file, startLine, endLine, code))

    }



    /**

     * Set the selected code context and update the preview card above the input.

     */

    internal fun consumeSelectedContext(): SelectedContext? {

        val ctx = selectedContext

        selectedContext = null

        return ctx

    }



    private fun setSelectedContext(context: SelectedContext?) {

        selectedContext = context

        selectedCodePreviewPanel.removeAll()



        if (context != null) {

            selectedCodePreviewPanel.add(

                SelectedCodePreview(

                    fileName = context.fileName,

                    startLine = context.startLine,

                    endLine = context.endLine,

                    onDismiss = { setSelectedContext(null) }

                )

            )

            selectedCodePreviewPanel.isVisible = true

        } else {

            selectedCodePreviewPanel.isVisible = false

        }



        selectedCodePreviewPanel.revalidate()

        selectedCodePreviewPanel.repaint()

    }



    /**

     * 从控制台/外部推送文本到聊天面板，以选中代码标签形式显示在输入框上方。

     * 由 [UploadConsoleAction] 等右键操作调用。

     */

    fun setConsoleContext(text: String) {

        val lineCount = text.lines().size

        setSelectedContext(SelectedContext("Console Output", 1, lineCount, text))

    }



    // ===== File attachment management =====



    private fun createSettingsButton(): JComponent {

        val btn = object : JButton() {

            override fun paintComponent(g: Graphics) {

                val g2 = g.create() as Graphics2D

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                g2.color = if (model.isRollover) JBColor(0xE0E0E0, 0x4A4A4A) else Color(0, 0, 0, 0)

                g2.fillRoundRect(0, 0, width, height, 8, 8)

                g2.color = JBColor(0x555555, 0xAAAAAA)

                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                val cx = width / 2

                val cy = height / 2

                val halfLen = 5

                val gap = 4

                for (i in 0..2) {

                    val y = cy + (i - 1) * gap

                    g2.drawLine(cx - halfLen, y, cx + halfLen, y)

                }

                g2.dispose()

            }

        }.apply {

            I18n.tooltip(this, "chat.settings")

            isOpaque = false

            isContentAreaFilled = false

            isBorderPainted = false

            isFocusPainted = false

            preferredSize = Dimension(24, 24)

            minimumSize = Dimension(24, 24)

            maximumSize = Dimension(24, 24)

            addActionListener {

                val popupMenu = JPopupMenu()



                val streamingItem = JCheckBoxMenuItem(I18n.tr("chat.streaming.toggle"), DeepSeekSettings.instance.streamingEnabled).apply {

                    addActionListener { DeepSeekSettings.instance.streamingEnabled = isSelected }

                }



                popupMenu.add(streamingItem)



                val thinkingItem = JCheckBoxMenuItem(I18n.tr("chat.thinking.toggle"), DeepSeekSettings.instance.thinkingEnabled).apply {

                    addActionListener { DeepSeekSettings.instance.thinkingEnabled = isSelected }

                }

                popupMenu.add(thinkingItem)

                // ── 输出速度微调器（↑↓ 箭头切换，不弹出列表） ──
                val speedLabel = JLabel(I18n.tr("chat.output.speed") + ":  ").apply {
                    font = font.deriveFont(11f)
                    foreground = JBColor(0x000000, 0xAAAAAA)
                }
                val speedLevels = listOf(
                    I18n.tr("chat.output.speed.fastest"),
                    I18n.tr("chat.output.speed.fast"),
                    I18n.tr("chat.output.speed.medium"),
                    I18n.tr("chat.output.speed.slow")
                )
                val speedSpinner = JSpinner(SpinnerListModel(speedLevels)).apply {
                    value = speedLevels[DeepSeekSettings.instance.outputSpeedLevel]
                    preferredSize = Dimension(100, 24)
                    addChangeListener {
                        val idx = speedLevels.indexOf(value)
                        if (idx >= 0) DeepSeekSettings.instance.outputSpeedLevel = idx
                    }
                    // 让编辑器不可编辑，只能通过箭头切换
                    editor = JSpinner.DefaultEditor(this).apply {
                        textField.isEditable = false
                        textField.font = textField.font.deriveFont(11f)
                    }
                }
                val speedPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(4, 8, 4, 8)
                    add(speedLabel)
                    add(speedSpinner)
                }
                popupMenu.add(speedPanel)
                popupMenu.addSeparator()



                val phaseItem = JMenuItem(I18n.tr("chat.agent.pipeline.settings"))

                phaseItem.addActionListener {

                    showSettingsPage("agentPipeline")

                }

                popupMenu.add(phaseItem)



                popupMenu.show(this, 0, -popupMenu.preferredSize.height)

            }

        }

        return btn

    }



    private fun createUploadButton(): JComponent {

        return createSmallRoundButton(AllIcons.Actions.Upload, "chat.upload") {

            openFileChooser()

        }

    }



    private fun createTranslateButton(): JComponent {

        return createSmallRoundButton(AllIcons.Actions.Preview, "chat.translate") {

            TranslateDialog(this@ChatPanel.project).show()

        }

    }



    /** 创建统一的小尺寸圆角图标按钮 */

    private fun createSmallRoundButton(icon: javax.swing.Icon, tooltipKey: String, action: () -> Unit): JComponent {

        return object : JButton(icon) {

            override fun paintComponent(g: Graphics) {

                val g2 = g.create() as Graphics2D

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.color = if (model.isRollover) JBColor(0xE0E0E0, 0x4A4A4A) else Color(0, 0, 0, 0)

                g2.fillRoundRect(0, 0, width, height, 8, 8)

                super.paintComponent(g)

                g2.dispose()

            }

        }.apply {

            I18n.tooltip(this, tooltipKey)

            isOpaque = false

            isContentAreaFilled = false

            isBorderPainted = false

            isFocusPainted = false

            preferredSize = Dimension(24, 24)

            minimumSize = Dimension(24, 24)

            maximumSize = Dimension(24, 24)

            addActionListener { action() }

        }

    }



    private fun openFileChooser() {

        val chooser = javax.swing.JFileChooser().apply {

            dialogTitle = I18n.tr("skill.choose.title")

            fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY

            isMultiSelectionEnabled = true

            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(

                "支持的格式 (*.java, *.kt, *.xml, *.json, *.yml, *.properties, *.txt, *.md, *.sql, *.gradle, *.ts, *.js, *.css, *.html, *.png, *.jpg, *.jpeg, *.gif, *.bmp, *.webp)",

                "java", "kt", "kts", "xml", "json", "yaml", "yml", "properties",

                "txt", "md", "sql", "gradle", "ts", "js", "css", "html", "py", "go", "rs", "rb",

                "png", "jpg", "jpeg", "gif", "bmp", "webp"

            )

        }



        val result = chooser.showOpenDialog(this)

        if (result == javax.swing.JFileChooser.APPROVE_OPTION) {

            val selectedFiles = chooser.selectedFiles

            for (file in selectedFiles) {

                addFileAttachment(file)

            }

        }

    }



    private fun addFileAttachment(file: java.io.File) {

        // Skip duplicates

        if (attachedFiles.any { it.absolutePath == file.absolutePath }) return



        val attached = AttachedFile(

            name = file.name,

            absolutePath = file.absolutePath,

            size = file.length()

        )

        attachedFiles.add(attached)

        refreshFileAttachmentPanel()

    }



    /** 供 PasteImageHandler 等外部组件调用：将图片文件添加为聊天附件 */

    fun addFileAttachmentFromExternal(file: java.io.File) {

        currentInstance?.addFileAttachment(file)

    }



    private fun removeFileAttachment(index: Int) {

        if (index in attachedFiles.indices) {

            attachedFiles.removeAt(index)

            refreshFileAttachmentPanel()

        }

    }



    private fun refreshFileAttachmentPanel() {

        fileAttachmentPanel.removeAll()



        if (attachedFiles.isNotEmpty()) {

            fileAttachmentPanel.add(

                FileAttachmentPreview(attachedFiles.toList()) { index -> removeFileAttachment(index) },

                BorderLayout.CENTER

            )

            fileAttachmentPanel.isVisible = true

        } else {

            fileAttachmentPanel.isVisible = false

        }



        fileAttachmentPanel.revalidate()

        fileAttachmentPanel.repaint()

    }



    /**

     * Image file extensions supported by StepFun image parsing.

     */

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")



    /**

     * Check if a file is an image by its extension.

     */

    private fun isImageFile(fileName: String): Boolean {

        val ext = fileName.substringAfterLast('.', "").lowercase()

        return ext in imageExtensions

    }



    /**

     * Build the file context string from attached text files only.

     * Image files are NOT processed here — they are handled asynchronously

     * in [sendMessageWithImages] so the UI shows "解析中..." instead of a modal dialog.

     */

    internal fun buildTextFileContext(): String {

        if (attachedFiles.isEmpty()) return ""



        val sb = StringBuilder()



        // Only process text files; image files are handled separately

        val textFiles = attachedFiles.filter { !isImageFile(it.name) }



        for (file in textFiles) {

            val content = file.readContent()

            if (content != null) {

                val ext = file.name.substringAfterLast('.', "")

                sb.appendLine("以下为文件 `${file.name}` 的内容：")

                sb.appendLine("```$ext")

                sb.appendLine(content)

                sb.appendLine("```")

                sb.appendLine()

            } else {

                sb.appendLine("[无法读取文件 `${file.name}`]")

            }

        }



        return sb.toString()

    }



    /**

     * Send a message with images asynchronously.

     *

     * Instead of showing a modal progress dialog, this method:

     * 1. Renders the user message with image file names shown inline

     * 2. Creates an AI streaming bubble with "解析中..." status

     * 3. Parses images in a background thread

     * 4. Once parsing completes, feeds the enriched text to the AI for final response

     * 5. The streaming bubble transitions from "解析中..." → AI response

     */

    private fun sendMessageWithImages(

        text: String,

        textFileContext: String,

        imageFiles: List<AttachedFile>,

        projectDir: java.io.File?,

        refContext: List<String>

    ) {

        val settings = DeepSeekSettings.instance



        // Check API key availability

        val apiKeyMissing = when (settings.imageParsingModel) {

            "stepfun" -> settings.stepFunApiKey.isBlank()

            "nvidia" -> settings.nvidiaApiKey.isBlank()

            else -> settings.agnesApiKey.isBlank()

        }

        if (apiKeyMissing) {

            val providerName = when (settings.imageParsingModel) {

                "stepfun" -> "StepFun"

                "nvidia" -> "NVIDIA"

                else -> "Agnes"

            }

            val msg = I18n.tr("chat.api.key.missing.prefix") + " " + providerName + I18n.tr("chat.api.key.missing.suffix")

            renderUserMessage(text)

            addMessageLabel(I18n.tr("chat.warning.prefix") + " " + msg)

            attachedFiles.clear()

            refreshFileAttachmentPanel()

            return

        }



        // Build final display text (without image descriptions yet)

        val imageNames = imageFiles.map { it.name }

        val finalText = buildString {

            if (textFileContext.isNotEmpty()) {

                append(textFileContext)

            }

            val ctx = selectedContext

            if (ctx != null) {

                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]")

                appendLine("```")

                appendLine(ctx.snippet)

                appendLine("```")

                appendLine()

            }

            if (refContext.isNotEmpty()) {

                appendLine(refContext.joinToString("\n\n"))

                appendLine()

            }

            append(text)

        }

        setSelectedContext(null)



        // Render user message with image file names as fileTabs

        renderUserMessageWithImages(finalText, imageNames)



        // Clear attachments

        attachedFiles.clear()

        refreshFileAttachmentPanel()



        // Create streaming bubble with "解析中..." status

        removeMessagesFiller()

        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val streamTextArea = createParsingArea(streamBubble, I18n.tr("chat.parsing"))

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        // Parse images on background thread

        val imagePaths = imageFiles.map { it.absolutePath }

        var isCancelled = false



        ApplicationManager.getApplication().executeOnPooledThread {

            try {

                val imageResults = mutableListOf<Pair<String, String>>()

                for (path in imagePaths) {

                    // Check for cancellation

                    if (isCancelled) break

                    val fileName = java.nio.file.Paths.get(path).fileName.toString()

                    val result = stepFunClient.parseImage(path)

                    val description = result.getOrElse { e -> "[图片解析失败: ${e.message}]" }

                    imageResults.add(fileName to description)

                }



                // Switch back to EDT to start AI streaming

                ApplicationManager.getApplication().invokeLater {

                if (isCancelled) {

                    stopThinkingAnimation()

                    chatState.set(ChatState.Idle)

                    messagesPanel.remove(streamBubble)

                    messagesPanel.revalidate()

                    sendStopButton.text = I18n.tr("chat.send.enter")

                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                    return@invokeLater

                }



                stopThinkingAnimation()



                // Build enriched text with image descriptions

                val imageContext = imageResults.joinToString("\n\n") { (name, desc) ->

                    "以下为图片 `$name` 的解析结果：" +

                    "\n> $desc"

                }

                val enrichedText = if (imageContext.isNotBlank()) {

                    "$textFileContext\n$imageContext\n$text"

                } else {

                    finalText

                }



                messageHistory.add(ChatMessage("user", enrichedText))

                currentSession().lastActiveTime = System.currentTimeMillis()

                saveSessions()



                // Set up streaming callbacks

                val tokenBuffer = StringBuilder()
                val reasoningBuffer = StringBuilder()
                val flushTimer = Timer(40) {
                    if (tokenBuffer.isNotEmpty()) {
                        val full = tokenBuffer.toString()
                        tokenBuffer.clear()
                        val maxChars = when (DeepSeekSettings.instance.outputSpeedLevel) {
                            1 -> 20
                            2 -> 8
                            3 -> 3
                            else -> Int.MAX_VALUE
                        }
                        val take = minOf(full.length, maxChars)
                        streamTextArea.append(full.substring(0, take))
                        if (take < full.length) tokenBuffer.append(full.substring(take))
                        streamTextArea.revalidate()
                        messagesPanel.revalidate()
                        scrollToBottom()
                    }
                }.apply { isRepeats = true }

                val onTokenBlock: (String) -> Unit = { token ->

                    ApplicationManager.getApplication().invokeLater {

                        if (thinkingTimer?.isRunning == true) {

                            thinkingTimer?.stop()

                            thinkingTimer = null

                            streamTextArea.text = ""

                        }

                        tokenBuffer.append(token)

                        val s = chatState.get()

                        if (s is ChatState.Streaming) s.buffer.append(token)

                        if (!flushTimer.isRunning) flushTimer.start()

                    }

                }



                val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->

                    ApplicationManager.getApplication().invokeLater {

                        flushTimer.stop()
                        if (tokenBuffer.isNotEmpty()) {
                            val remaining = tokenBuffer.toString()
                            tokenBuffer.clear()
                            streamTextArea.append(remaining)
                            streamTextArea.revalidate()
                            messagesPanel.revalidate()
                            scrollToBottom()
                        }
                        stopThinkingAnimation()

                        val oldState = chatState.getAndSet(ChatState.Idle)

                        if (oldState is ChatState.Streaming) {

                            oldState.eventSource.cancel()

                            removeStreamingArea(oldState)

                        }



                        val parsed = parseThinkingResponse(fullResponse)

                        val displayContent = parsed.second

                        val reasoning = if (DeepSeekSettings.instance.thinkingEnabled) (parsed.third ?: reasoningBuffer.toString().ifEmpty { null }) else null

                        messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning))

                        renderAssistantMessage(displayContent, reasoning = reasoning)



                        usage?.let {

                            currentSession().totalTokens += it.totalTokens

                            addMessageLabel(

                                "── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})"

                            )

                        }

                        saveSessions()

                        sendStopButton.text = I18n.tr("chat.send.enter")

                        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                        ensureMessagesFiller()

                        scrollToBottom()

                    }

                }



                val onErrorBlock: (Throwable) -> Unit = { error ->

                    ApplicationManager.getApplication().invokeLater {

                        flushTimer.stop()
                        if (tokenBuffer.isNotEmpty()) {
                            val remaining = tokenBuffer.toString()
                            tokenBuffer.clear()
                            streamTextArea.append(remaining)
                            streamTextArea.revalidate()
                            messagesPanel.revalidate()
                            scrollToBottom()
                        }
                        stopThinkingAnimation()

                        val oldState = chatState.getAndSet(ChatState.Idle)

                        if (oldState is ChatState.Streaming) {

                            oldState.eventSource.cancel()

                            removeStreamingArea(oldState)

                        }



                        val pluginErr = DeepSeekPluginException(

                            message = error.message ?: I18n.tr("chat.api.call.failed"),

                            cause = error,

                            userMessage = I18n.tr("chat.error.api") + " " + error.message

                        )

                        addMessageLabel(I18n.tr("chat.error.prefix") + " " + pluginErr.userMessage)

                        sendStopButton.text = I18n.tr("chat.send.enter")

                        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                        ensureMessagesFiller()

                        scrollToBottom()

                    }

                }



                // Build system prompt

                val qaSystemPrompt = buildString {

                    appendLine(DOMAIN_RESTRICTION_PROMPT)

                    appendLine()

                    appendLine("你是一个 AI 代码助手，帮助用户解答技术问题。")

                    appendLine()

                    appendLine("## 输出格式规范（必须遵守）")

                    appendLine("请按以下固定格式输出，不要修改标记：")

                    appendLine()

                    appendLine("<thinking>")

                    appendLine("此处填充你的思考推演过程：分析需求、权衡方案、排查问题的全部推理步骤")

                    appendLine("</thinking>")

                    appendLine()

                    appendLine("## 最终答案")

                    appendLine("此处放置面向用户的正式回复、代码、解决方案")

                    appendLine(if (DeepSeekSettings.instance.language == "en") "Please reply in English." else "请用中文回复。")

                    val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent(text)

                    if (skillsContent.isNotBlank()) {

                        append(skillsContent)

                    }

                    val searchResult = searchCoordinator.search(text)

                    if (searchResult.contextText.isNotBlank()) {

                        appendLine()

                        appendLine("## 项目上下文（搜索自当前项目）")

                        appendLine(searchResult.contextText)

                    }

                }



                // Start AI streaming with enriched context

                val eventSource = client.chatStream(

                    messages = listOf(ChatMessage("system", qaSystemPrompt)) + messageHistory.toList(),

                    onToken = onTokenBlock,

                    onComplete = onCompleteBlock,

                    onError = onErrorBlock,

                    onReasoningToken = { token -> reasoningBuffer.append(token)
                    val s = chatState.get()
                    if (s is ChatState.Streaming) s.reasoningBuffer.append(token)
                }

                )



                chatState.set(ChatState.Streaming(

                    eventSource = eventSource,

                    bubble = streamBubble,
                    flushTimer = flushTimer,
                    thinkingTimer = thinkingTimer

                ))

            }

        }
        catch (e: Exception) {

            System.err.println("[ChatPanel] Image parsing failed unexpectedly: ${e.message}")

            ApplicationManager.getApplication().invokeLater {

                stopThinkingAnimation()

                chatState.set(ChatState.Idle)

                messagesPanel.remove(streamBubble)

                messagesPanel.revalidate()

                sendStopButton.text = I18n.tr("chat.send.enter")

                sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                addMessageLabel(I18n.tr("chat.error.prefix") + " 图片解析异常: " + (e.message ?: "未知错误"))

                ensureMessagesFiller()

                scrollToBottom()

            }

        }

        }

    }



    // ===== Toolbar =====



    /**

     * Create a natural-looking toolbar/text button.

     * Modern style: subtle rounded rect background on hover, clean look.

     */

    // ===== Session management =====



    internal fun currentSession() = sessions[currentSessionIndex]



    private fun createNewSession() {

        // 当前会话为空时阻止创建新会话，给出友好提示

        if (sessions.isNotEmpty() && currentSession().messages.isEmpty()) {

            ToolWindowManager.getInstance(project).notifyByBalloon(

                "DeepSeek AI CodeHelper",

                MessageType.INFO,

                I18n.tr("chat.no.messages")

            )

            return

        }

        stopStreaming()

        sessionCounter++

        val name = I18n.tr("chat.session") + " $sessionCounter"

        sessions.add(ChatSession(name))

        val idx = sessions.size - 1

        sessionComboBox.addItem(name)

        currentSessionIndex = idx

        sessionComboBox.selectedIndex = idx

        messagesPanel.removeAll()

        userMessages.clear()

        showWelcome()

        saveSessions()

    }



    private fun clearAllSessions() {

        val result = JOptionPane.showConfirmDialog(

            this,

            I18n.tr("chat.confirm.clear.all.sessions"),

            I18n.tr("chat.clear.all.sessions"),

            JOptionPane.YES_NO_OPTION,

            JOptionPane.WARNING_MESSAGE

        )

        if (result != JOptionPane.YES_OPTION) return



        // Clear all sessions

        sessions.clear()

        sessionComboBox.removeAllItems()

        sessionCounter = 0



        // Reset to a fresh state

        messagesPanel.removeAll()

        userMessages.clear()



        // Create a new session and switch to it

        createNewSession()

    }



    fun switchToSession(index: Int) {

        stopStreaming()

        currentSessionIndex = index

        sessionComboBox.selectedIndex = index

        messagesPanel.removeAll()

        userMessages.clear()

        val session = sessions[index]

        if (session.messages.isEmpty()) {

            showWelcome()

            saveSessions()

            scrollToBottom()

            return

        }

        showMessages()

        ensureMessagesFiller()

        addMessageLabel("=== ${session.name} ===")



        // P3: 虚拟化 — 只渲染最近 VISIBLE_BATCH_SIZE 条消息

        val total = session.messages.size

        visibleStartIndex = maxOf(0, total - VISIBLE_BATCH_SIZE)

        renderMessageRange(session, visibleStartIndex, total)



        saveSessions()

        scrollToBottom()

    }



    /**

     * P3: 渲染指定范围内的消息（左闭右开）。

     * 如果前面还有未渲染的消息，在顶部添加"加载更多"按钮。

     */

    private fun renderMessageRange(session: ChatSession, start: Int, end: Int) {

        val messages = session.messages

        // 如果起始不是 0，在顶部插入"加载更多"按钮

        if (start > 0) {

            val loadMoreBtn = JButton(I18n.tr("chat.load.more")).apply {

                isOpaque = false

                foreground = PluginTheme.color(0x1A73E8, 0x64B5F6)

                font = font.deriveFont(Font.PLAIN, 11f)

                border = JBUI.Borders.empty(4, 0)

                addActionListener {

                    messagesPanel.removeAll()

                    userMessages.clear()

                    val newStart = maxOf(0, start - VISIBLE_BATCH_SIZE)

                    visibleStartIndex = newStart

                    addMessageLabel("=== ${session.name} ===")

                    renderMessageRange(session, newStart, end)

                    scrollToBottom()

                }

            }

            messagesPanel.add(loadMoreBtn, fillWidthConstraints)

            messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)

        }

        for (i in start until end) {

            val msg = messages[i]

            when (msg.role) {

                "user" -> renderUserMessage(msg.content)

                "assistant" -> renderAssistantMessage(msg.content, reasoning = msg.reasoning)

            }

        }

    }



    // ===== History dialog =====



    private fun showHistoryDialog() {

        val dialog = HistoryDialog(

            project, sessions, currentSessionIndex,

            onSwitch = { index -> switchToSession(index) },

            onClearAll = { clearAllSessions() }

        )

        dialog.show()

    }



    // ==================================================================

    // Mode toggle (Q&A / Agent)

    // ==================================================================



    /**

     * Create a dropdown (combobox) for selecting Q&A or Agent mode.

     * Fixed width = half the send button width.

     */

    private fun createModeDropdown(): JComponent {

        val combo = JComboBox(arrayOf(

            I18n.tr("chat.mode.qa"),

            I18n.tr("chat.mode.qa.scan"),

            I18n.tr("chat.mode.agent")

        )).apply {

            font = font.deriveFont(Font.PLAIN, 11f)

            isOpaque = false

            selectedIndex = when (currentMode) {

                ChatMode.Q_A -> 0

                ChatMode.Q_A_SCAN -> 1

                ChatMode.AGENT -> 2

            }

            addActionListener {

                val newMode = when (selectedIndex) {

                    1 -> ChatMode.Q_A_SCAN

                    2 -> ChatMode.AGENT

                    else -> ChatMode.Q_A

                }

                if (newMode != currentMode) {

                    currentMode = newMode

                }

            }

        }

        // 以最长选项文字"Q&A 全文扫描"的自然宽度为准，全局固定不随窗口伸缩

        val fixedW = combo.preferredSize.width

        val fixedH = combo.preferredSize.height

        combo.preferredSize = Dimension(fixedW, fixedH)

        combo.minimumSize = Dimension(fixedW, fixedH)

        combo.maximumSize = Dimension(fixedW, fixedH)

        return combo

    }



    // ==================================================================

    // Chat / Streaming

    // ==================================================================



    fun focusInput() {

        inputArea.requestFocusInWindow()

    }



    private fun sendMessage() {

        val text = inputArea.text.trim()

        val hasAttachments = attachedFiles.isNotEmpty() || selectedContext != null

        if (text.isEmpty() && !hasAttachments) return

        if (isStreaming) return

        // 发送新消息：恢复自动滚动跟随新回复
        autoScrollToBottom = true



        // 意图确认阶段 — 用户补充说明

        pendingConfirmation?.let { callback ->

            if (text.isNotBlank()) {

                inputArea.text = ""

                val cb = pendingConfirmation

                pendingConfirmation = null

                cb?.invoke(text)

            }

            return

        }



        if (currentMode == ChatMode.Q_A_SCAN) {

            sendQAScanMessage(text)

            return

        }



        if (currentMode == ChatMode.AGENT) {

            sendAgentMessage(text)

            return

        }



        val settings = DeepSeekSettings.instance

        if (settings.apiKey.isBlank() && settings.agnesApiKey.isBlank() && settings.nvidiaApiKey.isBlank()) {

            addMessageLabel(I18n.tr("chat.api.key.required"))

            return

        }



        inputArea.text = ""



        // 有图片 → 异步图片解析流程

        val imageFiles = attachedFiles.filter { isImageFile(it.name) }

        if (imageFiles.isNotEmpty()) {

            val fileContext = buildTextFileContext()

            val projectDir = project.basePath?.let { java.io.File(it) }

            val refContext = buildRefContext(projectDir, text)

            sendMessageWithImages(text, fileContext, imageFiles, projectDir, refContext)

            return

        }



        // 无图片 → 直接回答，无需项目检索

        attachedFiles.clear()

        refreshFileAttachmentPanel()

        respondDirectly(text)

        return

    }



    // ==================================================================

    // Q&A Full Scan mode — scan project context then respond as Q&A

    // ==================================================================



    /**

     * Q&A 全文扫描模式：先扫描项目获取相关上下文（文件结构 + 相关源码），

     * 再以 Q&A 方式回答（不走 Agent Pipeline）。

     */

    private fun sendQAScanMessage(userText: String) {

        val settings = DeepSeekSettings.instance

        if (settings.apiKey.isBlank() && settings.agnesApiKey.isBlank() && settings.nvidiaApiKey.isBlank()) {

            addMessageLabel(I18n.tr("chat.api.key.required"))

            return

        }



        inputArea.text = ""

        // 提取图片附件（全文扫描也要解析图片）
        val imageFiles = attachedFiles.filter { isImageFile(it.name) }
        val imagePaths = imageFiles.map { it.absolutePath }
        // 先从 attachedFiles 中移除图片（文本文件仍保留到 fileContext）
        attachedFiles.removeAll(imageFiles)

        // 1. 构建初始文本（包含编辑器选中代码 + 已上传文件上下文）

        val fileContext = buildTextFileContext()

        val initialText = buildString {

            val ctx = selectedContext

            if (ctx != null) {

                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]")

                appendLine("```")

                appendLine(ctx.snippet)

                appendLine("```")

                appendLine()

            }

            if (fileContext.isNotEmpty()) {

                append(fileContext)

                appendLine()

            }

            append(userText)

        }

        setSelectedContext(null)



        // 把用户消息展示到聊天区（含选中代码上下文）

        messageHistory.add(ChatMessage("user", initialText))

        renderUserMessage(initialText)

        currentSession().lastActiveTime = System.currentTimeMillis()

        saveSessions()



        // 2. 显示分析状态（使用旋转动画替代静态标签）

        val spinnerChars = listOf("◐", "◓", "◑", "◒")

        var spinnerIdx = 0

        val analysisLabel = JLabel().apply {

            font = font.deriveFont(Font.ITALIC, 11f)

            foreground = PluginTheme.textPrimary()

            border = JBUI.Borders.empty(4, 16, 4, 16)

            text = I18n.tr("chat.thinking") + " ◐"

        }

        messagesPanel.add(analysisLabel, fillWidthConstraints)

        revalidateAndScroll()

        val analysisAnimTimer = Timer(300) {

            spinnerIdx = (spinnerIdx + 1) % spinnerChars.size

            analysisLabel.text = I18n.tr("chat.thinking") + " " + spinnerChars[spinnerIdx]

        }.apply { start() }



        // 2. 后台扫描项目上下文（不经过 AI 判断，直接获取结构+搜索相关源码）

        ApplicationManager.getApplication().executeOnPooledThread {

            try {

                val projectStructure = buildProjectStructure()

                // 解析图片（如有）
                val imageContext = if (imagePaths.isNotEmpty()) {
                    val results = imagePaths.map { path ->
                        val fileName = java.nio.file.Paths.get(path).fileName.toString()
                        val description = stepFunClient.parseImage(path)
                            .getOrElse { e -> "[图片解析失败: " + e.message + "]" }
                        "图片 `" + fileName + "` 的解析结果：\n> " + description
                    }
                    "\n\n" + results.joinToString("\n\n---\n\n")
                } else ""

                val searchResult = searchCoordinator.search(userText)

                val relatedContext = searchResult.contextText



                val enrichedSearchContext = if (imageContext.isNotEmpty()) {
                    imageContext + "\n\n---\n\n" + relatedContext
                } else relatedContext

                val reasoningDetail = buildString {

                    appendLine("### 项目上下文扫描结果")

                    appendLine()

                    appendLine("用户问题：$userText")

                    appendLine()

                    if (projectStructure.isNotBlank()) {

                        appendLine("✅ 已扫描项目结构 (${projectStructure.lines().size} 行)")

                    }

                    if (relatedContext.isNotBlank()) {

                        appendLine("✅ 已搜索相关源文件 (${relatedContext.lines().size} 行)")

                    }

                    if (projectStructure.isBlank() && relatedContext.isBlank()) {

                        appendLine("⚠️ 未找到项目上下文")

                    }

                }



                val context = buildString {

                    if (projectStructure.isNotBlank()) {

                        appendLine("【项目结构参考】")

                        append(projectStructure)

                        appendLine()

                    }

                    if (relatedContext.isNotBlank()) {

                        appendLine("【相关源文件内容】")

                        append(relatedContext)

                    }

                }



                val finalText = if (context.isNotBlank()) {

                    "$context\n\n---\n\n$initialText"

                } else initialText



                // 5. 切回 EDT，展示分析思考过程 + 开始回答

                ApplicationManager.getApplication().invokeLater {

                    analysisAnimTimer.stop()

                    messagesPanel.remove(analysisLabel)



                    // 展示分析结果的折叠思考区域

                    if (reasoningDetail.isNotBlank()) {

                        val analysisBubble = MessageBubble(

                            project = project,

                            role = MessageBubble.Role.ASSISTANT,

                            content = I18n.tr("chat.analysis.complete"),

                            reasoning = if (DeepSeekSettings.instance.thinkingEnabled) reasoningDetail else null

                        )

                        messagesPanel.add(analysisBubble, fillWidthConstraints)

                        messagesPanel.add(Box.createVerticalStrut(12), fillWidthConstraints)

                        revalidateAndScroll()

                    }



                    // 更新 messageHistory 中的用户消息为富化版

                    if (messageHistory.isNotEmpty() && messageHistory.last().role == "user") {

                        messageHistory[messageHistory.lastIndex] = ChatMessage("user", finalText)

                    }



                    // 复用 respondDirectly（已渲染 + 已保存，传 userAlreadyRendered = true 跳过重复）

                    respondDirectly(finalText, userAlreadyRendered = true)

                }



            } catch (e: Exception) {

                ApplicationManager.getApplication().invokeLater {

                    analysisAnimTimer.stop()

                    messagesPanel.remove(analysisLabel)

                    addMessageLabel("⚠️ " + I18n.tr("chat.analysis.failed") + " ${e.message}")

                    respondDirectly(userText, userAlreadyRendered = true)

                }

            }

        }

    }



    // ==================================================================

    // Agent mode — scan project, call AI, apply file changes

    // ==================================================================



    /** Maximum file content length sent as context to the AI. */

    internal fun buildProjectStructure(maxFiles: Int = 30): String {

        val sb = StringBuilder()

        sb.appendLine("当前项目文件结构及内容如下：\n")

        try {

            val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots

            for (root in contentRoots) {

                collectFilesForContext(root.path, sb, maxFiles, 0)

            }

        } catch (_: Exception) {

            sb.appendLine("(无法读取项目文件结构)")

        }

        return sb.toString()

    }



    private fun collectFilesForContext(

        rootPath: String,

        sb: StringBuilder,

        maxFiles: Int,

        depth: Int

    ) {

        if (depth > 15) return

        val entries = try {

            fileAccess.listDirectory(rootPath, project)

        } catch (_: Exception) { return }

        for (entry in entries) {

            if (sb.count { it == '\n' } >= maxFiles * 3) return

            if (entry.isDirectory) {

                val name = entry.name

                if (name.startsWith(".") || name == "node_modules" || name == "build" ||

                    name == "target" || name == ".gradle" || name == "idea" ||

                    name == "out" || name == "dist" || name == ".git"

                ) continue

                collectFilesForContext(entry.path, sb, maxFiles, depth + 1)

            } else if (isSourceExt(entry.name.substringAfterLast('.', ""))) {

                val relativePath = entry.path

                val content = try {

                    fileAccess.readFile(entry.path, project)

                } catch (_: Exception) { null }

                if (content == null || content.length > 8000) continue

                sb.appendLine("--- $relativePath ---")

                sb.appendLine(content)

                sb.appendLine()

            }

        }

    }



    private fun isSourceExt(ext: String?): Boolean {

        return ext in listOf("java", "kt", "kts", "xml", "json", "yaml", "yml",

            "properties", "txt", "md", "sql", "gradle", "ts", "js", "css", "html",

            "py", "go", "rs", "rb", "php", "vue", "svelte", "swift", "ktm")

    }



    /**

     * 从 @文件名 引用中读取文件内容作为上下文。

     */

    private fun buildRefContext(projectDir: java.io.File?, text: String): List<String> {

        val refPattern = Regex("@([\\w.\\-/]+)")

        return refPattern.findAll(text).map { it.groupValues[1] }.toList().mapNotNull { refName ->

            projectDir?.let { dir ->

                val file = dir.resolve(refName)

                if (file.isFile && file.exists()) {

                    val content = file.readText().take(3000)

                    "## @$refName\n```\n$content\n```"

                } else null

            }

        }

    }



    /**

     * 直接回答模式：不读取源文件、不扫描目录，仅将用户问题发给 AI。

     */

    private fun respondDirectly(text: String, userAlreadyRendered: Boolean = false) {

        val settings = DeepSeekSettings.instance



        if (!userAlreadyRendered) {

            messageHistory.add(ChatMessage("user", text))

            renderUserMessage(text)

            currentSession().lastActiveTime = System.currentTimeMillis()

            saveSessions()

        }



        if (!settings.streamingEnabled) {

            respondDirectlySync(text, userAlreadyRendered)

            return

        }



        removeMessagesFiller()

        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val streamTextArea = createStreamingArea(streamBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        // ── 平滑输出：令牌缓冲队列 + 定时冲刷 ──

        val tokenBuffer = StringBuilder()

        val reasoningBuffer = StringBuilder()  // 累积 reasoning_content

        val flushTimer = Timer(40) {

            if (tokenBuffer.isNotEmpty()) {

                val full = tokenBuffer.toString()

                tokenBuffer.clear()

                val maxChars = when (DeepSeekSettings.instance.outputSpeedLevel) {
                    1 -> 20
                    2 -> 8
                    3 -> 3
                    else -> Int.MAX_VALUE
                }
                val take = minOf(full.length, maxChars)
                streamTextArea.append(full.substring(0, take))
                if (take < full.length) tokenBuffer.append(full.substring(take))

                streamTextArea.revalidate()

                messagesPanel.revalidate()

                scrollToBottom()

            }

        }.apply { isRepeats = true }



        val onTokenBlock: (String) -> Unit = { token ->

            ApplicationManager.getApplication().invokeLater {

                if (thinkingTimer?.isRunning == true) { thinkingTimer?.stop(); thinkingTimer = null; streamTextArea.text = "" }

                tokenBuffer.append(token)

                val s = chatState.get()

                if (s is ChatState.Streaming) s.buffer.append(token)

                if (!flushTimer.isRunning) flushTimer.start()

            }

        }

        val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->

            ApplicationManager.getApplication().invokeLater {

                flushTimer.stop()

                // 冲刷剩余缓冲

                if (tokenBuffer.isNotEmpty()) {

                    streamTextArea.append(tokenBuffer.toString())

                    tokenBuffer.clear()

                    streamTextArea.revalidate()

                    messagesPanel.revalidate()

                }

                stopThinkingAnimation()

                val oldState = chatState.getAndSet(ChatState.Idle)

                if (oldState is ChatState.Streaming) { oldState.eventSource.cancel(); removeStreamingArea(oldState) }

                // 解析响应中的思考推演过程与最终答案

                val parsed = parseThinkingResponse(fullResponse)

                val reasoning = if (DeepSeekSettings.instance.thinkingEnabled) (parsed.third ?: reasoningBuffer.toString().ifEmpty { null }) else null

                val displayContent = parsed.second

                messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning))

                renderAssistantMessage(displayContent, reasoning = reasoning)

                usage?.let { currentSession().totalTokens += it.totalTokens; addMessageLabel("── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})") }

                saveSessions(); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()

            }

        }

        val onErrorBlock: (Throwable) -> Unit = { error ->

            ApplicationManager.getApplication().invokeLater {

                flushTimer.stop()

                // 冲刷剩余缓冲

                if (tokenBuffer.isNotEmpty()) {

                    streamTextArea.append(tokenBuffer.toString())

                    tokenBuffer.clear()

                }

                stopThinkingAnimation()

                val oldState = chatState.getAndSet(ChatState.Idle)

                if (oldState is ChatState.Streaming) { oldState.eventSource.cancel(); removeStreamingArea(oldState) }

                addMessageLabel(I18n.tr("chat.error.prefix") + " " + error.message); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()

            }

        }



        val qaSystemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT); appendLine()

            appendLine("你是一个 AI 代码助手，帮助用户解答技术问题。")

            appendLine()

            appendLine("## 输出格式规范（必须遵守）")

            appendLine("请按以下固定格式输出，不要修改标记：")

            appendLine()

            appendLine("<thinking>")

            appendLine("此处填充你的思考推演过程：分析需求、权衡方案、排查问题的全部推理步骤")

            appendLine("</thinking>")

            appendLine()

            appendLine("## 最终答案")

            appendLine("此处放置面向用户的正式回复、代码、解决方案")

            appendLine(if (DeepSeekSettings.instance.language == "en") "Please reply in English." else "请用中文回复。")

            val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent(text)

            if (skillsContent.isNotBlank()) append(skillsContent)

            // 注入项目搜索上下文

            val searchResult = searchCoordinator.search(text)

            if (searchResult.contextText.isNotBlank()) {

                appendLine()

                appendLine("## 项目上下文（搜索自当前项目）")

                append(searchResult.contextText)

            }

        }



        val eventSource = client.chatStream(

            messages = listOf(ChatMessage("system", qaSystemPrompt)) + messageHistory.toList(),

            onToken = onTokenBlock, onComplete = onCompleteBlock, onError = onErrorBlock,

            onReasoningToken = { token -> reasoningBuffer.append(token)
            val s = chatState.get()
            if (s is ChatState.Streaming) s.reasoningBuffer.append(token)
        }

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = streamBubble))

    }



    /**

     * 同步回答模式（流式关闭时使用）：一次性调用 API，渲染完整结果。

     */

    private fun respondDirectlySync(text: String, userAlreadyRendered: Boolean = false) {

        val settings = DeepSeekSettings.instance

        val qaSystemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT); appendLine()

            appendLine("你是一个 AI 代码助手，帮助用户解答技术问题。")

            appendLine()

            appendLine("## 输出格式规范（必须遵守）")

            appendLine("请按以下固定格式输出，不要修改标记：")

            appendLine()

            appendLine("<thinking>")

            appendLine("此处填充你的思考推演过程：分析需求、权衡方案、排查问题的全部推理步骤")

            appendLine("</thinking>")

            appendLine()

            appendLine("## 最终答案")

            appendLine("此处放置面向用户的正式回复、代码、解决方案")

            appendLine(if (settings.language == "en") "Please reply in English." else "请用中文回复。")

            val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent(text)

            if (skillsContent.isNotBlank()) append(skillsContent)

            // 注入项目搜索上下文

            val searchResult = searchCoordinator.search(text)

            if (searchResult.contextText.isNotBlank()) {

                appendLine()

                appendLine("## 项目上下文（搜索自当前项目）")

                append(searchResult.contextText)

            }

        }



        addMessageLabel(I18n.tr("chat.info.syncing"))

        ApplicationManager.getApplication().executeOnPooledThread {

            val result = client.chatSync(listOf(ChatMessage("system", qaSystemPrompt)) + messageHistory.toList())

            ApplicationManager.getApplication().invokeLater {

                result.onSuccess { fullResponse ->

                    val parsed = parseThinkingResponse(fullResponse)

                    val displayContent = parsed.second

                    val reasoning = if (DeepSeekSettings.instance.thinkingEnabled) parsed.third else null

                    messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning))

                    renderAssistantMessage(displayContent, reasoning = reasoning)

                    currentSession().lastActiveTime = System.currentTimeMillis()

                    saveSessions()

                    ensureMessagesFiller()

                    scrollToBottom()

                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                }.onFailure { error ->

                    addMessageLabel(I18n.tr("chat.error.prefix") + " " + error.message)

                    sendStopButton.text = I18n.tr("chat.send.enter")

                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

                    ensureMessagesFiller()

                    scrollToBottom()

                }

            }

        }

    }



    private fun sendAgentMessage(userText: String) {

        pipelineTotalTokens = 0

        phase0RetryCount = 0

        val settings = DeepSeekSettings.instance



        inputArea.text = ""



        // Build file context and user message

        val fileContext = buildTextFileContext()

        val finalText = buildString {

            if (fileContext.isNotEmpty()) {

                append(fileContext)

            }

            val ctx = selectedContext

            if (ctx != null) {

                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]")

                appendLine("```")

                appendLine(ctx.snippet)

                appendLine("```")

                appendLine()

            }

            append(userText)

        }

        setSelectedContext(null)



        // 先将消息加入 session，再渲染气泡（确保删除按钮能正确找到消息索引）

        messageHistory.add(ChatMessage("user", finalText))

        renderUserMessage(finalText)

        currentSession().lastActiveTime = System.currentTimeMillis()

        saveSessions()



        // Build project structure context

        val projectStructure = buildProjectStructure()



        // ── 通过 SearchCoordinator 搜索相关上下文 ──

        val searchResult = searchCoordinator.search(finalText)

        val relatedContext = searchResult.contextText

        val hasRelatedContext = relatedContext.isNotEmpty()



        // Source roots hint

        val sourceRoots = getSourceRootPaths()

        val sourceRootsHint = if (sourceRoots.isNotEmpty()) {

            "项目的源码根目录有：\n" + sourceRoots.joinToString("\n") { "- $it" }

        } else ""



        // 获取技能内容（所有阶段共享）

        val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent(userText)



        // ════════════════════════════════════════════════════════════

        //  意图确认 Phase — 由 Agent Pipeline 配置决定

        // ════════════════════════════════════════════════════════════

        val p0Provider = LlmProviderRegistry.get(settings.agentPhase0Provider)

        val p0ApiKey = p0Provider.apiKey(settings.toSnapshot())



        val p1Provider = LlmProviderRegistry.get(settings.agentPhase1Provider)

        val p1Model = settings.agentPhase1Model



        if (settings.agentPhase0Enabled && p0ApiKey.isNotBlank()) {

            // Phase 0 已配置 → 先进行意图确认

            startIntentConfirmation(

                userText = userText,

                projectStructure = projectStructure,

                relatedContext = relatedContext,

                finalText = finalText,

                sourceRootsHint = sourceRootsHint,

                skillsContent = skillsContent

            )

        } else if (settings.agentPhase1Enabled) {

            // Phase 0 未配置/关闭 → 进入规划阶段

            addMessageLabel(phaseTransitionLabel(p1Model, "planning"))

            startPlanPhase(

                finalText = finalText,

                projectStructure = projectStructure,

                relatedContext = relatedContext,

                sourceRootsHint = sourceRootsHint,

                skillsContent = skillsContent

            )

        } else {

            // Phase 0 和 Phase 1 均关闭 → 直接进入编码阶段

            val p2Provider = LlmProviderRegistry.get(settings.agentPhase2Provider)

            val snap = settings.toSnapshot()
            val p2Config = Pair(p2Provider.baseUrl(snap), p2Provider.apiKey(snap))

            val p2Model = settings.agentPhase2Model

            addMessageLabel(I18n.tr("chat.agent.single.file"))

            addMessageLabel(phaseTransitionLabel(p2Model, "coding"))

            startCodePhase(

                p2Config = p2Config,

                p2Model = p2Model,

                planResponse = finalText,

                projectStructure = projectStructure,

                relatedContext = relatedContext,

                sourceRootsHint = sourceRootsHint,

                skillsContent = skillsContent,

                finalText = finalText,

                directFromPhase0 = true

            )

        }

    }



    // ════════════════════════════════════════════════════════════════

    //  意图确认 Phase — Agnes 理解需求，用户确认后才进入规划阶段

    // ════════════════════════════════════════════════════════════════



    /**

     * 意图确认与循环：

     * 1. 调用 Agnes 分析用户需求，生成一句话概括

     * 2. 显示「是/否」按钮让用户确认

     * 3. 是 → 进入规划 Agent

     * 4. 否 → 用户补充说明 → 结合上一次分析结果重新调用 Agnes → 回到步骤 2

     */

    private fun startIntentConfirmation(

        userText: String,

        projectStructure: String,

        relatedContext: String,

        finalText: String,

        sourceRootsHint: String,

        skillsContent: String,

        previousAnalysis: String? = null

    ) {

        val s = DeepSeekSettings.instance

        val p0Provider = LlmProviderRegistry.get(s.agentPhase0Provider)

        val snap = s.toSnapshot()
        val phase0Config = Pair(p0Provider.baseUrl(snap), p0Provider.apiKey(snap))

        val phase0Model = s.agentPhase0Model



        addMessageLabel("🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.understanding") + "...")



        // ═══ 动画加载指示器（旋转字符）替代静态标签 ═══

        val (analysisLabel, animTimer) = createAnalysisAnimation(p0Provider.displayName, "chat.agent.understanding")

        val analysisLabelAdded = messagesPanel.componentCount - 2



        val analysisPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("你是一个需求分析助手。分析用户对代码库的需求，只输出 JSON 对象（不要 markdown 代码块，不要其他文字）：")

            appendLine()

            appendLine("{")

            appendLine("  \"intent\": \"一句话概括用户的核心意图\",")

            appendLine("  \"isClear\": true,")

            appendLine("  \"complexity\": \"simple\",    // simple=简单任务 / complex=复杂任务")

            appendLine("  \"workload\": \"涉及的文件数量和改动范围\",")

            appendLine("  \"dependency\": \"文件间的依赖关系和耦合情况\",")

            appendLine("  \"reason\": \"综合工作量、关联性，说明为什么判定为简单或复杂\"")

            appendLine("}")

            appendLine("## 项目结构")

            appendLine(projectStructure)

            if (relatedContext.isNotBlank()) {

                appendLine("## 相关源文件")

                appendLine(relatedContext.take(1000))

            }

            appendLine()

            if (previousAnalysis != null) {

                appendLine("你之前的分析：$previousAnalysis")

                appendLine("用户补充说明：$userText")

                appendLine("请结合补充信息重新分析，按上述格式输出。")

            } else {

                appendLine("用户需求：$userText")

            }

        }



        // 在后台线程执行网络请求，避免阻塞 EDT

        ApplicationManager.getApplication().executeOnPooledThread {

            val result = client.chatSyncWithExplicitConfig(

                baseUrl = phase0Config.first,

                apiKey = phase0Config.second,

                model = phase0Model,

                temperature = 0.3,

                maxTokens = 512,

                messages = listOf(ChatMessage("user", analysisPrompt))

            )



            // UI 更新切回 EDT

            ApplicationManager.getApplication().invokeLater {

                animTimer.stop()

                if (analysisLabelAdded < messagesPanel.componentCount && messagesPanel.getComponent(analysisLabelAdded) === analysisLabel) {

                    messagesPanel.remove(analysisLabel)

                }

                revalidateAndScroll()

                result.onSuccess { interpretation ->

                    // 解析 AI 返回的 JSON

                    val cleanInterpretation = interpretation.trim()

                    System.err.println("[Phase0] raw response: $cleanInterpretation")

                    var intent: String = ""

                    var isClear: Boolean = true

                    var complexity: String = "complex"

                    var workload: String = ""

                    var dependency: String = ""

                    var reason: String = ""

                    try {

                        val jsonBody = cleanInterpretation

                            .substringAfter("{")

                            .let { it.substringBeforeLast("}") }

                        val jsonText = "{$jsonBody}"

                        fun extractStr(json: String, key: String): String? {

                            val r = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)

                            return r?.groupValues?.get(1)?.trim()

                        }

                        fun extractBool(json: String, key: String): Boolean? {

                            val r = Regex("\"$key\"\\s*:\\s*(true|false)").find(json)

                            return r?.groupValues?.get(1)?.toBoolean()

                        }

                        val rawComplexity = (extractStr(jsonText, "complexity") ?: "").lowercase()

                        intent = extractStr(jsonText, "intent")

                            ?: cleanInterpretation.removePrefix("分析：").removePrefix("分析结果：").trim()

                        isClear = extractBool(jsonText, "isClear") ?: true

                        complexity = if (rawComplexity in listOf("simple", "complex")) rawComplexity else "complex"

                        workload = extractStr(jsonText, "workload") ?: ""

                        dependency = extractStr(jsonText, "dependency") ?: ""

                        reason = extractStr(jsonText, "reason") ?: ""

                    } catch (e: Exception) {

                        System.err.println("[Phase0] JSON parse error: ${e.message}")

                        intent = cleanInterpretation.removePrefix("分析：").removePrefix("分析结果：").trim()

                    }



                    // 构建详细展示信息

                    val detailLines = buildString {

                        appendLine(intent)

                        if (workload.isNotEmpty()) appendLine("📊 工作量：$workload")

                        if (dependency.isNotEmpty()) appendLine("🔗 关联性：$dependency")

                        if (complexity == "simple") appendLine("📋 判定：简单任务 → 直接编码")

                        else appendLine("📋 判定：复杂任务 → 全流程执行")

                    }

                    addMessageLabel(I18n.tr("chat.agent.interprets") + " " + p0Provider.displayName)

                    addMessageLabel(detailLines)



                    // ── 确认按钮 ──

                    val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {

                        isOpaque = false

                        maximumSize = Dimension(Short.MAX_VALUE.toInt(), 40)

                    }



                    val p1ProviderLocal = LlmProviderRegistry.get(s.agentPhase1Provider)

                    val snap = s.toSnapshot()
                    val p1ConfigLocal = Pair(p1ProviderLocal.baseUrl(snap), p1ProviderLocal.apiKey(snap))

                    val p1ModelLocal = s.agentPhase1Model



                    val yesBtn = JButton(I18n.tr("chat.yes.this.is.what.i.mean")).apply {

                        addActionListener {

                            messagesPanel.remove(buttonPanel)

                            revalidateAndScroll()

                            if (complexity == "simple" || !s.agentPhase1Enabled) {

                                // 简单任务或 Phase 1 关闭 → 跳过规划与审查，直接进入编码阶段

                                addMessageLabel(I18n.tr("chat.agent.single.file"))

                                val p2Provider = LlmProviderRegistry.get(s.agentPhase2Provider)

                                val snap = s.toSnapshot()
                                val p2Config = Pair(p2Provider.baseUrl(snap), p2Provider.apiKey(snap))

                                val p2Model = s.agentPhase2Model

                                addMessageLabel(phaseTransitionLabel(p2Model, "coding"))

                                startCodePhase(

                                    p2Config = p2Config,

                                    p2Model = p2Model,

                                    planResponse = finalText,

                                    projectStructure = projectStructure,

                                    relatedContext = relatedContext,

                                    sourceRootsHint = sourceRootsHint,

                                    skillsContent = skillsContent,

                                    finalText = finalText,

                                    directFromPhase0 = true

                                )

                            } else {

                                // 复杂任务 → 全流程执行

                                addMessageLabel(phaseTransitionLabel(p1ModelLocal, "planning"))

                                startPlanPhase(

                                    finalText = finalText,

                                    projectStructure = projectStructure,

                                    relatedContext = relatedContext,

                                    sourceRootsHint = sourceRootsHint,

                                    skillsContent = skillsContent

                                )

                            }

                        }

                    }

                    val noBtn = JButton(I18n.tr("chat.no.i.need.to.supplement")).apply {

                        addActionListener {

                            messagesPanel.remove(buttonPanel)

                            revalidateAndScroll()

                            addMessageLabel(I18n.tr("chat.info.supplement"))



                            // 设置回调，等待用户在输入区补充说明

                            pendingConfirmation = { clarification ->

                                // 递归 — 用补充说明 + 上一次分析重新调用 Agnes

                                startIntentConfirmation(

                                    userText = clarification,

                                    projectStructure = projectStructure,

                                    relatedContext = relatedContext,

                                    finalText = finalText,

                                    sourceRootsHint = sourceRootsHint,

                                    skillsContent = skillsContent,

                                    previousAnalysis = intent

                                )

                            }

                        }

                    }



                    buttonPanel.add(yesBtn)

                    buttonPanel.add(noBtn)

                    messagesPanel.add(buttonPanel, fillWidthConstraints)

                    messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)

                    revalidateAndScroll()



                }.onFailure { error ->

                    if (phase0RetryCount < MAX_PHASE0_RETRIES) {

                        phase0RetryCount++

                        addMessageLabel("--- " + p0Provider.displayName + " retry ${phase0RetryCount}/${MAX_PHASE0_RETRIES} ---")

                        startIntentConfirmation(

                            userText = userText,

                            projectStructure = projectStructure,

                            relatedContext = relatedContext,

                            finalText = finalText,

                            sourceRootsHint = sourceRootsHint,

                            skillsContent = skillsContent

                        )

                        return@invokeLater

                    }

                    addMessageLabel(I18n.tr("chat.phase0.failed") + " " + error.message + I18n.tr("chat.phase0.failed.suffix"))

                    if (s.agentPhase1Enabled) {

                        val p1ProviderErr = LlmProviderRegistry.get(s.agentPhase1Provider)

                        val p1ModelErr = s.agentPhase1Model

                        addMessageLabel(phaseTransitionLabel(p1ModelErr, "planning"))

                        startPlanPhase(

                            finalText = finalText,

                            projectStructure = projectStructure,

                            relatedContext = relatedContext,

                            sourceRootsHint = sourceRootsHint,

                            skillsContent = skillsContent

                        )

                    } else {

                        addMessageLabel(I18n.tr("chat.agent.single.file"))

                        val p2ProviderErr = LlmProviderRegistry.get(s.agentPhase2Provider)

                        val snap = s.toSnapshot()
                        val p2ConfigErr = Pair(p2ProviderErr.baseUrl(snap), p2ProviderErr.apiKey(snap))

                        val p2ModelErr = s.agentPhase2Model

                        addMessageLabel(phaseTransitionLabel(p2ModelErr, "coding"))

                        startCodePhase(

                            p2Config = p2ConfigErr,

                            p2Model = p2ModelErr,

                            planResponse = finalText,

                            projectStructure = projectStructure,

                            relatedContext = relatedContext,

                            sourceRootsHint = sourceRootsHint,

                            skillsContent = skillsContent,

                            finalText = finalText,

                            directFromPhase0 = true

                        )

                    }

                }

            }

        }

    }



    // ════════════════════════════════════════════════════════════════

    //  Phase 1: 规划 Agent — DeepSeek-V4-Pro

    // ════════════════════════════════════════════════════════════════



    private fun startPlanPhase(

        finalText: String,

        projectStructure: String,

        relatedContext: String,

        sourceRootsHint: String,

        skillsContent: String

    ) {

        val s = DeepSeekSettings.instance

        val p1Provider = LlmProviderRegistry.get(s.agentPhase1Provider)

        val snap = s.toSnapshot()
        val p1Config = Pair(p1Provider.baseUrl(snap), p1Provider.apiKey(snap))

        val p1Model = s.agentPhase1Model



        // 构建规划阶段的系统提示（在 startPlanPhase 内部构建，避免与 sendAgentMessage 重复）

        val planSystemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("你是代码助手的规划 Agent（Planner）。你的任务是分析用户的需求，结合项目结构和相关源文件，制定详细的代码修改计划。")

            appendLine()

            appendLine("## 项目结构")

            appendLine(projectStructure)

            appendLine()

            if (relatedContext.isNotEmpty()) {

                appendLine("## 相关源文件内容")

                appendLine(relatedContext)

                appendLine()

            }

            if (sourceRootsHint.isNotEmpty()) {

                appendLine("## 项目源码根目录")

                appendLine(sourceRootsHint)

                appendLine()

            }

            appendLine("## 输出格式")

            appendLine("请输出一个结构化的计划，包含：")

            appendLine("1. 需要创建/修改/删除的文件列表，每个文件写明路径和操作类型")

            appendLine("2. 每个文件的详细修改要点或新增内容说明")

            appendLine("3. 依赖关系或注意事项（如需要先创建接口再实现等）")

            appendLine()

            appendLine("以清晰的 Markdown 格式输出。")

            if (skillsContent.isNotBlank()) {

                append(skillsContent)

            }

        }



        removeMessagesFiller()

        val planBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val planTextArea = createStreamingArea(planBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        val (onToken, onComplete, onError, onReasoningToken) = createPhaseCallbacks(

            textArea = planTextArea,

            errorLabelKey = I18n.tr("chat.planning.agent.error"),

            onPhaseComplete = { fullResponse, _ ->

                // ── 过渡到 Phase 2 ──

                val p2Provider = LlmProviderRegistry.get(s.agentPhase2Provider)

                val snap = s.toSnapshot()
                val p2Config = Pair(p2Provider.baseUrl(snap), p2Provider.apiKey(snap))

                val p2Model = s.agentPhase2Model

                addMessageLabel(phaseTransitionLabel(p2Model, "coding"))

                startCodePhase(p2Config, p2Model, fullResponse, projectStructure,

                    relatedContext, sourceRootsHint, skillsContent, finalText)

            }

        )



        val eventSource = client.chatStreamWithExplicitConfig(

            baseUrl = p1Config.first, apiKey = p1Config.second,

            model = p1Model, temperature = 0.7, maxTokens = 4096,

            messages = listOf(ChatMessage("system", planSystemPrompt), ChatMessage("user", finalText)),

            onToken = onToken, onComplete = onComplete, onError = onError,

            onReasoningToken = onReasoningToken

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = planBubble))

    }



    // ════════════════════════════════════════════════════════════════

    //  Phase 2: 编码 Agent — DeepSeek-V4-Flash

    // ════════════════════════════════════════════════════════════════



    private fun startCodePhase(

        p2Config: Pair<String, String>,

        p2Model: String,

        planResponse: String,

        projectStructure: String,

        relatedContext: String,

        sourceRootsHint: String,

        skillsContent: String,

        finalText: String,

        directFromPhase0: Boolean = false

    ) {

        removeMessagesFiller()

        val codeBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val codeTextArea = createStreamingArea(codeBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        val systemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("你是代码助手的编码 Agent（Coder）。你的任务是根据${if (directFromPhase0) "用户的需求" else "规划 Agent 制定的计划"}，生成具体的代码修改。")

            appendLine()

            appendLine("## 项目结构")

            appendLine(projectStructure)

            appendLine()

            if (relatedContext.isNotEmpty()) {

                appendLine("## 相关源文件内容")

                appendLine(relatedContext)

                appendLine()

            }

            if (sourceRootsHint.isNotEmpty()) {

                appendLine("## 项目源码根目录")

                appendLine(sourceRootsHint)

                appendLine()

                appendLine("**重要：创建新文件时必须使用上述已存在的源码根目录之一，不要创建新的源码根目录。**")

                appendLine()

            }

            appendLine(if (directFromPhase0) "## 用户需求" else "## 规划 Agent 的计划")

            appendLine(planResponse)

            appendLine()

            appendLine("## 输出格式")

            appendLine("对于每个需要创建或修改的文件，请使用以下格式：")

            appendLine()

            appendLine("<file path=\"相对路径/文件名.扩展名\">")

            appendLine("```语言名")

            appendLine("文件内容...")

            appendLine("```")

            appendLine("</file>")

            appendLine()

            appendLine("如果要删除文件，使用：")

            appendLine("<file path=\"相对路径/文件名.扩展名\" action=\"delete\"></file>")

            appendLine()

            appendLine("## 规则")

            appendLine("1. 所有路径都是相对于项目根目录的")

            appendLine("2. 对于已有的文件，修改时基于相关源文件内容进行增删改，输出**完整的新文件内容**（不要省略）")

            appendLine("3. [重要] 创建新文件时，必须放在已有包目录下，严禁创建新的一级源码目录")

            appendLine("4. 优先使用项目中已有的框架、模式和编码风格")

            appendLine("5. 输出文件修改后，简要总结你做了什么改动")

            appendLine("6. 项目启动类必须放在 controller、service、entity 等包的同级目录下，不可放在子包中")

            if (skillsContent.isNotBlank()) append(skillsContent)

        }



        val (onToken, onComplete, onError, onReasoningToken) = createPhaseCallbacks(

            textArea = codeTextArea,

            errorLabelKey = I18n.tr("chat.coding.agent.error"),

            onPhaseComplete = { fullResponse, _ ->

                // 解析并执行文件操作

                val operations = fileOperationExecutor.parseFileOperations(fullResponse)

                if (operations.isNotEmpty()) {

                    fileOperationExecutor.applyFileOperations(operations, planResponse)

                }



                // ── 过渡到 Phase 3 ──

                val s = DeepSeekSettings.instance

                if (directFromPhase0 || !s.agentPhase3Enabled) {

                    addMessageLabel(I18n.tr("chat.info.skip.review"))

                    finalizeAgentSession()

                } else {

                    val p3Provider = LlmProviderRegistry.get(s.agentPhase3Provider)

                    val p3ApiKey = p3Provider.apiKey(s.toSnapshot())

                    if (p3ApiKey.isNotBlank()) {

                        val p3Config = Pair(p3Provider.baseUrl(s.toSnapshot()), p3ApiKey)

                        val p3Model = s.agentPhase3Model

                        addMessageLabel(phaseTransitionLabel(p3Model, "reviewing"))

                        startReviewPhase(p3Config, p3Model, planResponse, fullResponse)

                    } else {

                        addMessageLabel(I18n.tr("chat.info.skip.review"))

                        finalizeAgentSession()

                    }

                }

            }

        )



        val eventSource = client.chatStreamWithExplicitConfig(

            baseUrl = p2Config.first, apiKey = p2Config.second,

            model = p2Model, temperature = 0.7, maxTokens = 8192,

            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", "请根据上面的规划生成代码。")),

            onToken = onToken, onComplete = onComplete, onError = onError,

            onReasoningToken = onReasoningToken

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = codeBubble))

    }



    // ════════════════════════════════════════════════════════════════

    //  Phase 3: 审查 Agent — Agnes-2.0-Flash

    // ════════════════════════════════════════════════════════════════



    private fun startReviewPhase(

        reviewConfig: Pair<String, String>,

        reviewModel: String,

        planResponse: String,

        codeResponse: String

    ) {

        removeMessagesFiller()

        val reviewBubble = MessageBubble(project, MessageBubble.Role.STREAMING)

        val reviewTextArea = createStreamingArea(reviewBubble)

        sendStopButton.text = I18n.tr("chat.stop.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")



        val systemPrompt = buildString {

            appendLine(DOMAIN_RESTRICTION_PROMPT)

            appendLine()

            appendLine("你是一个专业的代码审查 Agent（Reviewer）。你的任务是审查编码 Agent 生成的代码修改。")

            appendLine()

            appendLine("## 原始规划")

            appendLine(planResponse)

            appendLine()

            appendLine("## 生成的代码")

            appendLine(codeResponse)

            appendLine()

            appendLine("## 审查要点")

            appendLine("请从以下维度进行审查：")

            appendLine("1. ✅ **正确性**: 代码是否正确实现了规划中的需求？")

            appendLine("2. 🔒 **安全性**: 是否存在安全漏洞？")

            appendLine("3. ⚡ **性能**: 是否存在性能问题？")

            appendLine("4. 📐 **编码规范**: 是否符合编码规范？")

            appendLine("5. 📋 **完整性**: 是否所有文件都已正确生成？")

            appendLine()

            appendLine("如果发现问题，指出问题位置和修改建议。如果没有问题，给出总体评价。")

        }



        val (onToken, onComplete, onError, onReasoningToken) = createPhaseCallbacks(

            textArea = reviewTextArea,

            errorLabelKey = I18n.tr("chat.review.agent.error"),

            onPhaseComplete = { _, usage ->

                usage?.let {

                    addMessageLabel("── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})")

                }

                finalizeAgentSession()

            }

        )



        val eventSource = client.chatStreamWithExplicitConfig(

            baseUrl = reviewConfig.first, apiKey = reviewConfig.second,

            model = reviewModel, temperature = 0.3, maxTokens = 2048,

            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", "请审查上述代码修改。")),

            onToken = onToken, onComplete = onComplete, onError = onError,

            onReasoningToken = onReasoningToken

        )

        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = reviewBubble))

    }



    /** 清理当前流式状态并重置发送按钮。 */

    internal fun cleanupStreamingAndResetButton() {

        val oldState = chatState.getAndSet(ChatState.Idle)

        if (oldState is ChatState.Streaming) {

            oldState.eventSource.cancel()

            removeStreamingArea(oldState)

        }

        sendStopButton.text = I18n.tr("chat.send.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

    }



    /** 完成 Agent 会话的收尾工作（保存、填充）。 */

    internal fun finalizeAgentSession() {

        saveSessions()

        if (pipelineTotalTokens > 0) {

            addMessageLabel("--- Pipeline Total: ${pipelineTotalTokens} tokens ---")

        }

        sendStopButton.text = I18n.tr("chat.send.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

        ensureMessagesFiller()

        scrollToBottom()

    }



    internal fun getSourceRootPaths(): List<String> {

        val roots = mutableListOf<String>()

        try {

            // Source roots (src/main/java, src/main/kotlin, etc.)

            val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots

            for (root in sourceRoots) {

                roots.add(root.path.replace('\\', '/'))

            }

            // Also include content roots (the module root)

            val contentRoots = ProjectRootManager.getInstance(project).contentRoots

            for (root in contentRoots) {

                val path = root.path.replace('\\', '/')

                if (path !in roots) roots.add(path)

            }

        } catch (_: Exception) { }



        // Also scan the file system for common source directories

        val basePath = project.basePath ?: return roots

        val baseDir = java.io.File(basePath)

        val commonDirs = listOf(

            "src/main/java", "src/main/kotlin", "src/test/java", "src/test/kotlin",

            "src/main/resources", "src/test/resources"

        )

        for (dir in commonDirs) {

            val f = java.io.File(baseDir, dir)

            if (f.exists()) {

                val path = f.canonicalPath.replace('\\', '/')

                if (path !in roots) roots.add(path)

            }

        }



        return roots

    }



    private fun stopThinkingAnimation() {

        thinkingTimer?.stop()

        thinkingTimer = null

    }

    private fun stopStreaming() {

        stopThinkingAnimation()

        val oldState = chatState.getAndSet(ChatState.Idle)

        if (oldState is ChatState.Streaming) {

            // 停止所有运行中的 Timer，防止它们操作已移除的组件
            oldState.flushTimer?.stop()
            oldState.thinkingTimer?.stop()

            oldState.eventSource.cancel()

            // 读取已累积的流式内容，渲染为完整的 ASSISTANT 气泡
            val partialContent = oldState.buffer.toString()
            val partialReasoning = oldState.reasoningBuffer.toString().ifEmpty { null }

            if (partialContent.isNotBlank()) {
                val parsed = parseThinkingResponse(partialContent)
                val displayContent = parsed.second
                val reasoning = if (DeepSeekSettings.instance.thinkingEnabled)
                    (parsed.third ?: partialReasoning) else null
                messageHistory.add(ChatMessage("assistant", displayContent, reasoning = reasoning))
                renderAssistantMessage(displayContent, reasoning = reasoning)
                saveSessions()
            }

            removeStreamingArea(oldState)
        }

        sendStopButton.text = I18n.tr("chat.send.enter")

        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")

        ensureMessagesFiller()

        scrollToBottom()

    }



    internal fun saveSessions() {

        // 防抖：500ms 内多次调用合并为一次磁盘写入

        saveTimer.restart()

    }



    /** 实际的持久化逻辑（由 debounce timer 触发） */

    private fun doSaveSessions() {

        currentSession().lastActiveTime = System.currentTimeMillis()

        sessionStore.save(sessions, sessionCounter)

    }



    // ════════════════════════════════════════════════════════════════

    //  CardLayout navigation — chat view ↔ settings view

    // ════════════════════════════════════════════════════════════════



    /**

     * Switch back to the main chat view from the settings panel.

     */

    fun showChatView() {

        (layout as CardLayout).show(this, "chat")

    }



    /**

     * Switch to the unified settings panel, showing the given sub-page.

     */

    fun showSettingsPage(pageKey: String) {

        (layout as CardLayout).show(this, "settings")

        unifiedSettingsPanel.showPage(pageKey)

    }



    /**

     * Switch to the unified settings panel.

     */

    private fun showSkillSettings() {

        showSettingsPage("skillSettings")

    }



    /**

     * Switch to the full-coverage change management panel,

     * and refresh the record list from the in-memory store.

     */

    private fun showChangeManagement() {

        changeManagementPanel.refreshRecords()

        (layout as CardLayout).show(this, "changeManagement")

    }



    override fun dispose() {

        currentInstance = null

        stopThinkingAnimation()

        // 关闭时同步保存（跳过 debounce，确保数据落盘）

        saveTimer.stop()

        doSaveSessions()

    }



    // ════════════════════════════════════════════════════════════════

    //  Agentic Search 辅助方法

    // ════════════════════════════════════════════════════════════════



    /**

     * 判断用户查询是否与代码相关。

     * 代码查询使用 Agentic Search，文档查询使用 RAG。

     */

    private fun isCodeQuery(query: String): Boolean {

        // 包含 CamelCase 类名/函数名

        val camelCasePattern = Regex("""\b[A-Z][a-zA-Z0-9]{2,}\b""")

        if (camelCasePattern.containsMatchIn(query)) return true



        // 包含代码关键词

        val codeKeywords = listOf(

            "class", "function", "method", "interface", "enum", "annotation",

            "import", "package", "extends", "implements", "override",

            "controller", "service", "mapper", "repository", "entity",

            "dto", "vo", "po", "bo", "config", "handler", "util",

            "getter", "setter", "constructor", "bean", "component",

            "api", "rest", "endpoint", "route", "mapping",

            "数据库", "表", "字段", "接口", "实现", "继承",

            "get", "set", "find", "search", "query", "update", "save", "delete", "create"

        )

        val queryLower = query.lowercase()

        for (kw in codeKeywords) {

            if (queryLower.contains(kw)) return true

        }



        // 包含以 ., #, :: 连接的可能代码路径

        val codePathPattern = Regex("""[\w.]+\.\w+""")

        if (codePathPattern.containsMatchIn(query)) return true



        return false

    }



    /**

     * 单轮 Agentic Search：提取关键词 → grep → 构建上下文。

     * 用于单轮模式（agenticSearchMaxRounds <= 1）。

     */

    private fun buildCodeSearchContext(query: String): String {

        // 提取搜索关键词

        val keywords = extractSearchKeywords(query)

        if (keywords.isEmpty()) return ""



        val sb = StringBuilder()

        val seen = mutableSetOf<String>()



        for (kw in keywords.take(MAX_SEARCH_KEYWORDS)) {

            if (kw in seen) continue

            seen.add(kw)



            val result = agenticSearch.grep(kw)

            if (result.matches.isEmpty()) continue



            sb.appendLine("### 搜索: `$kw`（共 ${result.totalMatches} 条匹配）")

            sb.appendLine()



            // 按文件分组展示

            val byFile = result.matches.groupBy { it.filePath }

            val entries = byFile.entries.take(5)

            for (entry in entries) {

                val filePath = entry.key

                val matches = entry.value

                sb.appendLine("📄 `$filePath`:")

                for (matchItem in matches.take(5)) {

                    val lineSnippet = matchItem.lineText.take(150)

                    sb.appendLine("  L${matchItem.lineNumber}: ${lineSnippet}")

                }

                if (matches.size > 5) {

                    sb.appendLine("  ... (还有 ${matches.size - 5} 条)")

                }

                sb.appendLine()

            }

        }



        return sb.toString()

    }



    /**

     * 从用户查询中提取搜索关键词。

     */

    private fun extractSearchKeywords(query: String): List<String> {

        val keywords = mutableSetOf<String>()



        // CamelCase 类/函数名

        val camelCasePattern = Regex("""\b[A-Z][a-zA-Z0-9]{2,}\b""")

        keywords.addAll(camelCasePattern.findAll(query).map { it.value })



        // 引号中的内容

        val quotePattern = Regex("""[""']([^""']{2,})[""']""")

        keywords.addAll(quotePattern.findAll(query).map { it.groupValues[1] })



        // 点符号路径 get.user.by.id → getUserById 等

        val dotPattern = Regex("""\b([a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+)\b""")

        for (match in dotPattern.findAll(query)) {

            keywords.add(match.value.replace(".", ""))

        }



        // 小写方法名（已知前缀）

        val methodPattern = Regex("""\b(get|set|find|search|query|update|save|delete|create|remove|add|is|has)[A-Z][a-zA-Z0-9]+\b""")

        keywords.addAll(methodPattern.findAll(query).map { it.value })



        return keywords.toList().filter { it.length >= 2 }

    }



    /**

     * 根据用户请求文本，静默搜索相关源文件并读取完整内容。

     * 返回格式化后的字符串，供 Agent 系统 prompt 使用。

     */

    internal fun buildRelatedFileContext(userText: String): String {

        val keywords = extractSearchKeywords(userText)

        if (keywords.isEmpty()) return ""



        val seen = mutableSetOf<String>()

        val sb = StringBuilder()

        val projectBase = project.basePath ?: return ""



        for (kw in keywords.take(MAX_SEARCH_KEYWORDS)) {

            if (kw in seen) continue

            seen.add(kw)



            val result = agenticSearch.grep(kw)

            if (result.matches.isEmpty()) continue



            val byFile = result.matches.groupBy { it.filePath }

            for ((filePath, matches) in byFile.entries.take(MAX_FILES_PER_KEYWORD)) {

                if (sb.count { it == '\n' } > MAX_CONTEXT_LINES) return sb.toString()

                val file = java.io.File(filePath)

                if (!file.exists() || !file.isFile) continue

                val content = try {

                    file.readText(Charsets.UTF_8)

                } catch (_: Exception) { continue }

                if (content.length > MAX_FILE_SIZE) continue

                val relativePath = file.toRelativeString(java.io.File(projectBase))

                sb.appendLine("--- $relativePath ---")

                sb.appendLine(content.trim())

                sb.appendLine()

            }

        }

        return sb.toString()

    }



    // ════════════════════════════════════════════════════════════════

    //  UI 展示截断工具

    // ════════════════════════════════════════════════════════════════



    /**

     * 截断字符串中所有 ```代码块``` 的内容为前 [maxLines] 行。

     * 仅影响 UI 展示，不影响完整消息内容。

     * 格式：

     *   ```lang

     *   第1行

     *   ...

     *   第N行 (N > maxLines 时)

     *   ......

     *   ```

     */

    private fun truncateCodeBlocks(text: String, maxLines: Int = 20): String {

        val codeBlockRegex = Regex("""```(\w*)\s*\n?([\s\S]*?)```""")

        return codeBlockRegex.replace(text) { match ->

            val lang = match.groupValues[1]

            val code = match.groupValues[2]

            val lines = code.lines()



            if (lines.size > maxLines) {

                val truncated = lines.take(maxLines).joinToString("\n")

                "```$lang\n$truncated\n......\n```"

            } else {

                // 不超过 maxLines 行，原样保留

                match.value

            }

        }

    }



    // ==================================================================

    // Component builders — modular code block cards

    // ==================================================================



    internal fun scrollToBottom() {

        // 用户已向上滚动查看历史：不强制拉回底部
        if (!autoScrollToBottom) return

        // 确保布局更新，使滚动条值准确
        messagesScrollPane.validate()

        // 如果用户已手动向上滚动，不强制拉回底部
        val vsb = messagesScrollPane.verticalScrollBar
        val isAtBottom = vsb == null || !vsb.isVisible ||
            vsb.value + vsb.model.extent >= vsb.maximum - 30
        if (!isAtBottom) return

        SwingUtilities.invokeLater {

            // 优先让最后一条可见的消息呈现到视野中——比直接设 maximum 更可靠

            for (i in messagesPanel.componentCount - 1 downTo 0) {

                val c = messagesPanel.getComponent(i)

                val rect = c.bounds

                if (rect != null && rect.width > 0 && rect.height > 0) {

                    messagesPanel.scrollRectToVisible(rect)

                    return@invokeLater

                }

            }

            // 兜底：直接设置滚动条

            messagesScrollPane.verticalScrollBar.value =

                messagesScrollPane.verticalScrollBar.maximum

        }

    }



    /**

     * Add a simple text label (system messages, tokens, errors).

     */

    internal fun addMessageLabel(text: String) {

        showMessages()

        val label = JBTextArea(text).apply {

            isEditable = false

            lineWrap = true

            wrapStyleWord = true

            font = JBUI.Fonts.create("Monospaced", 12)

            foreground = PluginTheme.textPrimary()

            background = messagesPanel.background

            margin = JBUI.insets(4, 8, 4, 8)

            border = JBUI.Borders.empty()

            // Ensure label fills available width

            alignmentX = Component.LEFT_ALIGNMENT

        }

        messagesPanel.add(label, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(2), fillWidthConstraints)

        revalidateAndScroll()

    }



    /**

     * Render a user message as a modern right-aligned chat bubble,

     * with an ✕ delete button in the top-right corner.

     */

    internal fun renderUserMessage(content: String) {

        showMessages()



        // 计算这条用户消息在 session.messages 中的索引

        val session = currentSession()

        val userMsgIndices = session.messages.indices

            .filter { session.messages[it].role == "user" }

        val currentUserIdx = userMessages.size

        val msgIndex = userMsgIndices.getOrNull(currentUserIdx)



        val onDeleteAction = if (msgIndex != null) {

            {

                deleteMessagePair(session, msgIndex)

            }

        } else null



        // 气泡展示时截断代码块为前 20 行（仅 UI 展示，不影响传给 LLM 的完整内容）

        val displayContent = truncateCodeBlocks(content, maxLines = 20)



        val bubble = MessageBubble(

            project, MessageBubble.Role.USER, displayContent,

            onDelete = onDeleteAction

        )

        // 气泡直接加入 messagesPanel（MessageBubble 内部自绘对齐和圆角背景）

        messagesPanel.add(bubble, fillWidthConstraints)

        // 消息间距

        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)



        // Track for nav sidebar (only user messages) — 导航侧栏保留完整内容

        userMessages.add(MessageEntry(bubble, content))

        revalidateAndScroll()

    }



    /**

     * Render a user message that includes image file names as file tabs.

     * Images are shown inline in the bubble while they're being parsed in the background.

     */

    private fun renderUserMessageWithImages(content: String, imageNames: List<String>) {

        showMessages()



        // 计算这条用户消息在 session.messages 中的索引

        val session = currentSession()

        val userMsgIndices = session.messages.indices

            .filter { session.messages[it].role == "user" }

        val currentUserIdx = userMessages.size

        val msgIndex = userMsgIndices.getOrNull(currentUserIdx)



        val onDeleteAction = if (msgIndex != null) {

            { deleteMessagePair(session, msgIndex) }

        } else null



        // 气泡展示时截断代码块为前 20 行（仅 UI 展示，不影响传给 LLM 的完整内容）

        val displayContent = truncateCodeBlocks(content, maxLines = 20)



        val bubble = MessageBubble(

            project, MessageBubble.Role.USER, displayContent,

            onDelete = onDeleteAction,

            fileTabs = imageNames

        )

        messagesPanel.add(bubble, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)



        userMessages.add(MessageEntry(bubble, content))

        revalidateAndScroll()

    }



    /**

     * Render an assistant message — card style with accent bar + avatar.

     */

    internal fun renderAssistantMessage(content: String, reasoning: String? = null) {

        showMessages()

        val bubble = MessageBubble(project, MessageBubble.Role.ASSISTANT, content, reasoning = reasoning)

        // 气泡直接加入 messagesPanel

        messagesPanel.add(bubble, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(12), fillWidthConstraints)



        // Link this response to the most recent user question without a response yet

        val lastUserMsg = userMessages.lastOrNull { it.responsePanel == null }

        lastUserMsg?.responsePanel = bubble



        revalidateAndScroll()

    }



    /**

     * Create a temporary streaming message bubble.

     * Shows "思考中... ◐" spinning animation until the first token arrives.

     */

    internal fun createStreamingArea(bubble: MessageBubble): JBTextArea {

        // 气泡直接加入 messagesPanel

        messagesPanel.add(bubble, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(12), fillWidthConstraints)

        revalidateAndScroll()



        // ── Start thinking animation ──

        val textArea = bubble.streamTextArea!!

        spinnerIndex = 0

        thinkingTimer = Timer(300) {

            spinnerIndex = (spinnerIndex + 1) % spinnerChars.size

            textArea.text = I18n.tr("chat.thinking") + " " + spinnerChars[spinnerIndex]

        }.apply { start() }

        return textArea

    }



    /**

     * Create a streaming bubble with a custom initial status text (e.g. "解析中...").

     * Used for the image parsing phase before AI streaming begins.

     */

    private fun createParsingArea(bubble: MessageBubble, statusText: String = I18n.tr("chat.parsing")): JBTextArea {

        messagesPanel.add(bubble, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(12), fillWidthConstraints)

        revalidateAndScroll()



        val textArea = bubble.streamTextArea!!

        spinnerIndex = 0

        thinkingTimer = Timer(300) {

            spinnerIndex = (spinnerIndex + 1) % spinnerChars.size

            textArea.text = "... $statusText ${spinnerChars[spinnerIndex]}"

        }.apply { start() }

        return textArea

    }



    /** Remove the streaming bubble and stop the thinking animation. */

    internal fun removeStreamingArea(state: ChatState.Streaming) {

        val bubble = state.bubble

        // 气泡目前已直接位于 messagesPanel 中（无 padded 包装层）

        messagesPanel.remove(bubble)

        messagesPanel.revalidate()

        messagesPanel.repaint()

    }



    // ════════════════════════════════════════════════════════════════

    //  Anchor dot timeline (left-side blue dots)

    // ════════════════════════════════════════════════════════════════



    // ════════════════════════════════════════════════════════════════

    //  (旧版圆点时间线已移除 — 改用现代气泡卡片布局)

    // ════════════════════════════════════════════════════════════════



    /**

     * 删除一条用户消息及其对应的 AI 回复，然后重新渲染会话。

     */

    private fun deleteMessagePair(session: ChatSession, userMsgIndex: Int) {

        val indicesToRemove = mutableListOf(userMsgIndex)

        // 如果下一条是 AI 回复，一起删除

        val nextIdx = userMsgIndex + 1

        if (nextIdx < session.messages.size && session.messages[nextIdx].role == "assistant") {

            indicesToRemove.add(nextIdx)

        }

        // 从后往前删，保持索引有效

        indicesToRemove.sortedDescending().forEach { session.messages.removeAt(it) }



        // 重新渲染整个会话

        messagesPanel.removeAll()

        userMessages.clear()

        if (session.messages.isEmpty()) {

            showWelcome()

        } else {

            showMessages()

            ensureMessagesFiller()

            addMessageLabel("=== ${session.name} ===")

            renderMessageRange(session, 0, session.messages.size)

        }

        saveSessions()

        scrollToBottom()

    }



    internal fun revalidateAndScroll() {

        messagesPanel.revalidate()

        messagesPanel.repaint()

        scrollToBottom()

    }



    /**

     * Remove the vertical filler from messagesPanel, so messages take their

     * natural height and the scroll pane viewport expands gradually as content

     * streams in (little-by-little expansion).

     */

    internal fun removeMessagesFiller() {

        messagesPanel.remove(verticalFiller)

    }



    /**

     * Ensure the vertical filler is present at the end of messagesPanel.

     * The filler has weighty=1.0 so it expands to take any extra vertical space,

     * pushing messages to the top and eliminating blank space below.

     */

    internal fun ensureMessagesFiller() {

        for (c in messagesPanel.components) {

            if (c === verticalFiller) return

        }

        messagesPanel.add(verticalFiller, GridBagConstraints().apply {

            fill = GridBagConstraints.BOTH

            weightx = 1.0

            weighty = 1.0

            gridwidth = GridBagConstraints.REMAINDER

        })

    }



    // ════════════════════════════════════════════════════════════════

    //  Shared animation helper — extracted to eliminate duplication

    // ════════════════════════════════════════════════════════════════



    /**

     * Creates a spinning-character animation label and appends it to [messagesPanel].

     * The label shows "🤔 {displayName} {statusText} ◐" with a rotating spinner.

     *

     * @param providerDisplayName Provider display name shown in the label

     * @param statusKey           i18n key for the status text (e.g. "chat.agent.analyzing")

     * @return Pair of (label, timer) — caller must stop the timer and remove the label when done

     */

    internal fun createAnalysisAnimation(providerDisplayName: String, statusKey: String): Pair<JBTextArea, Timer> {

        val animChars = "◐◓◑◒"

        val label = JBTextArea("🤔 $providerDisplayName ${I18n.tr(statusKey)} ◐").apply {

            isEditable = false

            lineWrap = true

            wrapStyleWord = true

            font = JBUI.Fonts.create("Monospaced", 12)

            foreground = PluginTheme.textPrimary()

            background = messagesPanel.background

            margin = JBUI.insets(4, 8, 4, 8)

            border = JBUI.Borders.empty()

            alignmentX = Component.LEFT_ALIGNMENT

        }

        messagesPanel.add(label, fillWidthConstraints)

        messagesPanel.add(Box.createVerticalStrut(2), fillWidthConstraints)

        revalidateAndScroll()



        val timer = Timer(300) {

            val idx = (System.currentTimeMillis() / 300).toInt() % 4

            label.text = "🤔 $providerDisplayName ${I18n.tr(statusKey)} ${animChars[idx]}"

        }

        timer.start()

        return Pair(label, timer)

    }



    // ════════════════════════════════════════════════════════════════

    //  Shared streaming callback factory — extracted to eliminate duplication

    // ════════════════════════════════════════════════════════════════



    /**

     * Creates standard streaming callbacks (onToken, onComplete, onError) for pipeline phases.

     * Handles EDT scheduling, streaming cleanup, message persistence, and token tracking.

     *

     * @param textArea       The text area to append tokens to

     * @param errorLabelKey  i18n key for the error message label

     * @param onPhaseComplete Phase-specific logic to run after the common onComplete handling

     * @return Triple of (onToken, onComplete, onError)

     */

    internal fun createPhaseCallbacks(

        textArea: JBTextArea,

        errorLabelKey: String,

        onPhaseComplete: (fullResponse: String, usage: Usage?) -> Unit

    ): PhaseCallbacksWithReasoning {

        val reasoningBuffer = StringBuilder()
        val tokenBuffer = StringBuilder()
        val flushTimer = Timer(40) {
            if (tokenBuffer.isNotEmpty()) {
                val full = tokenBuffer.toString()
                tokenBuffer.clear()
                val maxChars = when (DeepSeekSettings.instance.outputSpeedLevel) {
                    1 -> 20
                    2 -> 8
                    3 -> 3
                    else -> Int.MAX_VALUE
                }
                val take = minOf(full.length, maxChars)
                textArea.append(full.substring(0, take))
                if (take < full.length) tokenBuffer.append(full.substring(take))
                messagesPanel.revalidate()
                scrollToBottom()
            }
        }.apply { isRepeats = true }

        val onToken: (String) -> Unit = { token ->

            ApplicationManager.getApplication().invokeLater {
                if (thinkingTimer?.isRunning == true) { thinkingTimer?.stop(); thinkingTimer = null; textArea.text = "" }
                tokenBuffer.append(token)
                if (!flushTimer.isRunning) flushTimer.start()
            }

        }

        val onReasoningToken: (String) -> Unit = { token ->

            reasoningBuffer.append(token)

        }

        val onComplete: (String, Usage?) -> Unit = { fullResponse, usage ->

            ApplicationManager.getApplication().invokeLater {
                flushTimer.stop()
                if (tokenBuffer.isNotEmpty()) {
                    textArea.append(tokenBuffer.toString())
                    tokenBuffer.clear()
                    messagesPanel.revalidate()
                    scrollToBottom()
                }

                if (chatState.get() is ChatState.Streaming)

                    cleanupStreamingAndResetButton()

                val reasoning = reasoningBuffer.toString().ifEmpty { null }

                messageHistory.add(ChatMessage("assistant", fullResponse, reasoning = reasoning))

                renderAssistantMessage(fullResponse, reasoning = reasoning)

                usage?.let { currentSession().totalTokens += it.totalTokens }

                onPhaseComplete(fullResponse, usage)

            }

        }

        val onError: (Throwable) -> Unit = { error ->

            ApplicationManager.getApplication().invokeLater {
                flushTimer.stop()
                if (tokenBuffer.isNotEmpty()) {
                    textArea.append(tokenBuffer.toString())
                    tokenBuffer.clear()
                    messagesPanel.revalidate()
                    scrollToBottom()
                }

                if (chatState.get() is ChatState.Streaming)

                    cleanupStreamingAndResetButton()

                addMessageLabel("$errorLabelKey ${error.message}")

                ensureMessagesFiller(); scrollToBottom()

            }

        }
        return PhaseCallbacksWithReasoning(onToken, onComplete, onError, onReasoningToken)

    }



    /** Callback bundle returned by [createPhaseCallbacks], includes reasoning token handler. */

    internal data class PhaseCallbacksWithReasoning(

        val onToken: (String) -> Unit,

        val onComplete: (String, Usage?) -> Unit,

        val onError: (Throwable) -> Unit,

        val onReasoningToken: (String) -> Unit

    )



    /** Format a phase transition label: "{prefix}{model}{suffix}" using i18n keys. */

    private fun phaseTransitionLabel(model: String, phaseKey: String): String =

        I18n.tr("chat.agent.${phaseKey}.prefix") + model + I18n.tr("chat.agent.${phaseKey}.suffix")



    // ════════════════════════════════════════════════════════════════

    //  思考推演过程解析 — 从 AI 响应中提取 <thinking> 块与最终答案

    // ════════════════════════════════════════════════════════════════



    /**

     * 解析 AI 响应，提取思考推演过程和最终答案。

     *

     * @param response 原始 AI 响应文本

     * @return Triple(原始响应, 展示内容, 思考推演文本)

     *         - first:  原始完整响应

     *         - second: 最终答案部分（移除 <thinking> 块后的内容）

     *         - third:  思考推演文本（<thinking> 块内内容），无 thinking 块时为 null

     */

    private fun parseThinkingResponse(response: String): Triple<String, String, String?> {

        val thinkingRegex = Regex("<thinking>([\\s\\S]*?)</thinking>", RegexOption.MULTILINE)

        val match = thinkingRegex.find(response)



        if (match == null) {

            // 没有 thinking 块 → 整段作为最终答案

            return Triple(response, response, null)

        }



        val thinkingContent = match.groupValues[1].trim()

        // 移除 <thinking> 块，剩余部分作为展示内容

        val displayContent = response.replace(thinkingRegex, "").trim()



        return Triple(response, displayContent.ifEmpty { response }, thinkingContent.ifEmpty { null })

    }

}

