package com.deepseek.plugin.chat

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT
import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.DeepSeekPluginException
import com.deepseek.plugin.api.LlmProviderRegistry
import com.deepseek.plugin.api.StepFunApiClient
import com.deepseek.plugin.api.Usage
import com.deepseek.plugin.chat.ChatState
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.context.ProjectContextProvider
import com.deepseek.plugin.context.RagRetriever
import com.deepseek.plugin.search.AgenticSearch
import com.deepseek.plugin.search.ToolUseEngine
import com.deepseek.plugin.store.SessionStore
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.store.ChangeManagementStore
import com.deepseek.plugin.store.ChangeRecord
import com.deepseek.plugin.store.FileChangeInfo
import com.deepseek.plugin.ui.AttachedFile
import com.deepseek.plugin.ui.ChangeManagementPanel
import com.deepseek.plugin.ui.ChatInputBar
import com.deepseek.plugin.ui.ChatToolbar
import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.FileAttachmentPreview
import com.deepseek.plugin.ui.HistoryDialog
import com.deepseek.plugin.ui.MessageBubble
import com.deepseek.plugin.ui.ResponseSegment
import com.deepseek.plugin.ui.SelectedCodePreview
import com.deepseek.plugin.ui.SessionBar
import com.deepseek.plugin.ui.UnifiedSettingsPanel
import com.deepseek.plugin.ui.TranslateDialog
import com.deepseek.plugin.ui.WelcomePanel
import com.intellij.icons.AllIcons
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
import com.intellij.openapi.vfs.LocalFileSystem
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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultHighlighter
import javax.swing.Timer

enum class ChatMode {
    Q_A, AGENT
}

data class ChatSession(
    val name: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var totalTokens: Int = 0,
    var lastActiveTime: Long = System.currentTimeMillis()
)

class ChatPanel(private val project: Project) : JPanel(CardLayout()), Disposable {

    companion object {
        /** 当前活动的 ChatPanel 实例，供外部 Action 向聊天面板推送内容 */
        @JvmStatic
        var currentInstance: ChatPanel? = null
            private set
    }

    private val client = DeepSeekApiClient()
    private val stepFunClient = StepFunApiClient()
    private val contextProvider = ProjectContextProvider(project)
    private val ragRetriever = RagRetriever(project)
    private val agenticSearch = AgenticSearch(project)
    private val sessionStore = SessionStore(project.basePath)
    private val sessions = mutableListOf<ChatSession>()
    private var currentSessionIndex = 0
    /** 线程安全的聊天状态机 — 替代 isStreaming + currentEventSource + streamBuffer + streamingBubble + … */
    private val chatState = AtomicReference<ChatState>(ChatState.Idle)
    private var sessionCounter = 1
    private var currentMode = ChatMode.Q_A

    /** 意图确认阶段的回调 — 非 null 时正在等待用户补充说明 */
    private var pendingConfirmation: ((clarification: String) -> Unit)? = null

    /** 统一设置页面 — 覆盖整个插件区域，含顶部图标导航栏 */
    private val unifiedSettingsPanel: UnifiedSettingsPanel

    /** 变更管理面板 — 覆盖整个插件区域 */
    private val changeManagementPanel: ChangeManagementPanel

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
    private data class SelectedContext(
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
    private val messagesPanel = JPanel().apply {
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
    }

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

    private val inputArea = AutoResizingTextArea(4, 0, project, { sendMessage() }, { isStreaming },
        onImagePasted = { file -> addFileAttachment(file) }
    )

    // ── Combined send/stop button ──

    private val sendStopButton = JButton(I18n.tr("chat.send.enter")).apply {
        toolTipText = I18n.tr("chat.tooltip.send")
        addActionListener { if (isStreaming) stopStreaming() else sendMessage() }
    }

    private val messageHistory: MutableList<ChatMessage>
        get() = currentSession().messages

    init {
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
            background = JBColor(0xFFFFFF, 0x1E1E1E)
        }
        messagesScrollPane.background = JBColor(0xFFFFFF, 0x1E1E1E)
        messagesPanel.background = JBColor(0xFFFFFF, 0x1E1E1E)
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

        // ── Wrap the entire chat view into one panel for CardLayout ──
        val chatView = JPanel(BorderLayout()).apply {
            isOpaque = true
            add(topBar, BorderLayout.NORTH)
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
                // 圆角悬停背景
                g2.color = if (model.isRollover) JBColor(0xE0E0E0, 0x4A4A4A) else Color(0, 0, 0, 0)
                g2.fillRoundRect(0, 0, width, height, 8, 8)
                // 绘制三条横线（hamburger menu 图标）
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
            toolTipText = I18n.tr("chat.tooltip.mode.select")
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = Dimension(24, 24)
            minimumSize = Dimension(24, 24)
            maximumSize = Dimension(24, 24)
            addActionListener {
                val popupMenu = JPopupMenu()

                val qaItem = JRadioButtonMenuItem(I18n.tr("chat.mode.qa"))
                val agentItem = JRadioButtonMenuItem(I18n.tr("chat.mode.agent"))
                val modeGroup = ButtonGroup()
                modeGroup.add(qaItem)
                modeGroup.add(agentItem)
                qaItem.isSelected = currentMode == ChatMode.Q_A
                agentItem.isSelected = currentMode == ChatMode.AGENT

                qaItem.addActionListener { currentMode = ChatMode.Q_A }
                agentItem.addActionListener { currentMode = ChatMode.AGENT }

                popupMenu.add(qaItem)
                popupMenu.add(agentItem)
                popupMenu.addSeparator()

                val phaseItem = JMenuItem(I18n.tr("chat.agent.pipeline.settings"))
                phaseItem.addActionListener {
                    showSettingsPage("agentPipeline")
                }
                popupMenu.add(phaseItem)

                popupMenu.show(this, 0, height)
            }
        }
        return btn
    }

    private fun createUploadButton(): JComponent {
        return createSmallRoundButton(AllIcons.Actions.Upload, I18n.tr("chat.upload")) {
            openFileChooser()
        }
    }

    private fun createTranslateButton(): JComponent {
        return createSmallRoundButton(AllIcons.Actions.Preview, I18n.tr("chat.translate")) {
            TranslateDialog(this@ChatPanel.project).show()
        }
    }

    /** 创建统一的小尺寸圆角图标按钮 */
    private fun createSmallRoundButton(icon: javax.swing.Icon, tooltip: String, action: () -> Unit): JComponent {
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
            toolTipText = tooltip
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
    private fun buildTextFileContext(): String {
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
                val onTokenBlock: (String) -> Unit = { token ->
                    ApplicationManager.getApplication().invokeLater {
                        if (thinkingTimer?.isRunning == true) {
                            thinkingTimer?.stop()
                            thinkingTimer = null
                            streamTextArea.text = ""
                        }
                        streamTextArea.append(token)
                        streamTextArea.revalidate()
                        messagesPanel.revalidate()
                        scrollToBottom()
                    }
                }

                val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->
                    ApplicationManager.getApplication().invokeLater {
                        stopThinkingAnimation()
                        val oldState = chatState.getAndSet(ChatState.Idle)
                        if (oldState is ChatState.Streaming) {
                            oldState.eventSource.cancel()
                            removeStreamingArea(oldState)
                        }

                        messageHistory.add(ChatMessage("assistant", fullResponse))
                        renderAssistantMessage(fullResponse)

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
                    appendLine(if (DeepSeekSettings.instance.language == "en") "Please reply in English." else "请用中文回复。")
                    val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent()
                    if (skillsContent.isNotBlank()) {
                        append(skillsContent)
                    }
                }

                // Start AI streaming with enriched context
                val eventSource = client.chatStream(
                    messages = listOf(ChatMessage("system", qaSystemPrompt)) + messageHistory.toList(),
                    onToken = onTokenBlock,
                    onComplete = onCompleteBlock,
                    onError = onErrorBlock
                )

                chatState.set(ChatState.Streaming(
                    eventSource = eventSource,
                    bubble = streamBubble
                ))
            }
        }
    }

    // ===== Toolbar =====

    /**
     * Create a natural-looking toolbar/text button.
     * Modern style: subtle rounded rect background on hover, clean look.
     */
    // ===== Session management =====

    private fun currentSession() = sessions[currentSessionIndex]

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
                foreground = JBColor(0x1A73E8, 0x64B5F6)
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
                "assistant" -> renderAssistantMessage(msg.content)
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
        val combo = JComboBox(arrayOf(I18n.tr("chat.mode.qa"), I18n.tr("chat.mode.agent"))).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            // Let IntelliJ LAF handle colors for natural look
            isOpaque = false
            // Fixed width for mode selector
            val fixedW = 100
            val fixedH = preferredSize.height
            preferredSize = Dimension(fixedW, fixedH)
            maximumSize = Dimension(fixedW, fixedH)
            minimumSize = Dimension(fixedW, fixedH)
            addActionListener {
                val newMode = if (selectedIndex == 0) ChatMode.Q_A else ChatMode.AGENT
                if (newMode != currentMode) {
                    currentMode = newMode
                    // tooltip removed — no hover popup on buttons
                }
            }
        }
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

        // 无图片 → 先意图分类，再决定是否读取源文件
        attachedFiles.clear()
        refreshFileAttachmentPanel()
        classifyAndRespond(text)
        return
    }

    // ==================================================================
    // Agent mode — scan project, call AI, apply file changes
    // ==================================================================

    /** Maximum file content length sent as context to the AI. */
    private fun buildProjectStructure(maxFiles: Int = 30): String {
        val sb = StringBuilder()
        sb.appendLine("当前项目文件结构及内容如下：\n")
        try {
            val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots
            var count = 0
            for (root in contentRoots) {
                collectFilesForContext(root, root, sb, maxFiles, 0)
            }
        } catch (_: Exception) {
            sb.appendLine("(无法读取项目文件结构)")
        }
        return sb.toString()
    }

    private fun collectFilesForContext(
        root: VirtualFile,
        dir: VirtualFile,
        sb: StringBuilder,
        maxFiles: Int,
        depth: Int
    ) {
        if (depth > 15) return
        for (child in dir.children ?: return) {
            if (sb.count { it == '\n' } >= maxFiles * 3) return
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name == "node_modules" || name == "build" ||
                    name == "target" || name == ".gradle" || name == "idea" ||
                    name == "out" || name == "dist" || name == ".git"
                ) continue
                collectFilesForContext(root, child, sb, maxFiles, depth + 1)
            } else if (isSourceExt(child.extension)) {
                val relativePath = child.path.substring(root.path.length).trimStart('/')
                val content = try {
                    String(child.contentsToByteArray(), Charsets.UTF_8)
                } catch (_: Exception) { continue }
                if (content.length > 8000) continue // skip very large files
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
     * Q&A 意图分类：先调用 Phase 0 Provider 判断用户输入类型，
     * 然后决定是直接回答（无需上下文）还是读取源文件 + 扫描目录后回答。
     */
    private fun classifyAndRespond(text: String) {
        val settings = DeepSeekSettings.instance
        val p0Provider = LlmProviderRegistry.get(settings.agentPhase0Provider)
        val p0ApiKey = p0Provider.apiKey(settings)

        if (p0ApiKey.isBlank()) {
            respondWithContext(text)
            return
        }

        val p0BaseUrl = p0Provider.baseUrl(settings)
        val p0Model = settings.agentPhase0Model

        // ═══ FIX: 立即渲染用户消息气泡，消除黑屏 ═══
        renderUserMessage(text)
        messageHistory.add(ChatMessage("user", text))
        currentSession().lastActiveTime = System.currentTimeMillis()
        saveSessions()
        removeMessagesFiller()

        // ═══ FIX: 动画加载指示器（旋转字符）替代静态标签 ═══
        val animChars = "◐◓◑◒"
        val analysisLabel = JBTextArea("🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.analyzing") + " ◐").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 12)
            foreground = JBColor(0x666666, 0x999999)
            background = messagesPanel.background
            margin = JBUI.insets(4, 8, 4, 8)
            border = JBUI.Borders.empty()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        messagesPanel.add(analysisLabel, fillWidthConstraints)
        messagesPanel.add(Box.createVerticalStrut(2), fillWidthConstraints)
        revalidateAndScroll()

        val animTimer = Timer(300) {
            val idx = (System.currentTimeMillis() / 300).toInt() % 4
            analysisLabel.text = "🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.analyzing") + " ${animChars[idx]}"
        }
        animTimer.start()

        ApplicationManager.getApplication().executeOnPooledThread {
            val classificationPrompt = buildString {
                appendLine(DOMAIN_RESTRICTION_PROMPT)
                appendLine()
                appendLine("你是一个输入分类器。判断用户的输入属于哪一类，只输出一个词：")
                appendLine("- general: 普通编程问题、技术问答、概念解释、代码示例请求（不需要读取项目文件）")
                appendLine("- context_needed: 控制台报错、异常堆栈、用户选中的代码片段、需要参考项目中的具体文件")
                appendLine()
                appendLine("用户输入：")
                appendLine(text)
                appendLine()
                appendLine("分类：")
            }

            val result = client.chatSyncWithExplicitConfig(
                baseUrl = p0BaseUrl,
                apiKey = p0ApiKey,
                model = p0Model,
                temperature = 0.1,
                maxTokens = 20,
                messages = listOf(ChatMessage("user", classificationPrompt))
            )

            ApplicationManager.getApplication().invokeLater {
                animTimer.stop()
                messagesPanel.remove(analysisLabel)
                revalidateAndScroll()

                result.onSuccess { response ->
                    val intent = response.trim().lowercase().takeWhile { it.isLetter() }
                    if (intent == "general") {
                        addMessageLabel(I18n.tr("chat.info.general.question"))
                        respondDirectly(text, userAlreadyRendered = true)
                    } else {
                        addMessageLabel(I18n.tr("chat.info.context.needed"))
                        respondWithContext(text, userAlreadyRendered = true)
                    }
                }.onFailure {
                    addMessageLabel(I18n.tr("chat.info.classification.failed"))
                    respondDirectly(text, userAlreadyRendered = true)
                }
            }
        }
    }

    /**
     * 直接回答模式：不读取源文件、不扫描目录，仅将用户问题发给 AI。
     */
    private fun respondDirectly(text: String, userAlreadyRendered: Boolean = false) {
        val settings = DeepSeekSettings.instance

        if (!userAlreadyRendered) {
            renderUserMessage(text)
            messageHistory.add(ChatMessage("user", text))
            currentSession().lastActiveTime = System.currentTimeMillis()
            saveSessions()
        }

        removeMessagesFiller()
        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val streamTextArea = createStreamingArea(streamBubble)
        sendStopButton.text = I18n.tr("chat.stop.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")

        val onTokenBlock: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                if (thinkingTimer?.isRunning == true) { thinkingTimer?.stop(); thinkingTimer = null; streamTextArea.text = "" }
                streamTextArea.append(token); streamTextArea.revalidate(); messagesPanel.revalidate(); scrollToBottom()
            }
        }
        val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
                stopThinkingAnimation()
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) { oldState.eventSource.cancel(); removeStreamingArea(oldState) }
                messageHistory.add(ChatMessage("assistant", fullResponse)); renderAssistantMessage(fullResponse)
                usage?.let { currentSession().totalTokens += it.totalTokens; addMessageLabel("── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})") }
                saveSessions(); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()
            }
        }
        val onErrorBlock: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                stopThinkingAnimation()
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) { oldState.eventSource.cancel(); removeStreamingArea(oldState) }
                addMessageLabel(I18n.tr("chat.error.prefix") + " " + error.message); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()
            }
        }

        val qaSystemPrompt = buildString {
            appendLine(DOMAIN_RESTRICTION_PROMPT); appendLine()
            appendLine("你是一个 AI 代码助手，帮助用户解答技术问题。")
            appendLine(if (DeepSeekSettings.instance.language == "en") "Please reply in English." else "请用中文回复。")
            val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent()
            if (skillsContent.isNotBlank()) append(skillsContent)
        }

        val eventSource = client.chatStream(
            messages = listOf(ChatMessage("system", qaSystemPrompt)) + messageHistory.toList(),
            onToken = onTokenBlock, onComplete = onCompleteBlock, onError = onErrorBlock
        )
        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = streamBubble))
    }

    /**
     * 全量模式：读取附件的文件上下文、@引用文件、选中代码、
     * 以及 Agentic Search / RAG 检索，然后发给 AI 回答。
     */
    private fun respondWithContext(text: String, userAlreadyRendered: Boolean = false) {
        val settings = DeepSeekSettings.instance
        val projectDir = project.basePath?.let { java.io.File(it) }
        val refPattern = Regex("@([\\w.\\-/]+)")

        val fileContext = buildTextFileContext()
        val refContext = buildRefContext(projectDir, text)

        val finalText = buildString {
            if (fileContext.isNotEmpty()) { append(fileContext) }
            val ctx = selectedContext
            if (ctx != null) {
                appendLine("[${ctx.fileName}:${ctx.startLine}-${ctx.endLine}]"); appendLine("```"); appendLine(ctx.snippet); appendLine("```"); appendLine()
            }
            if (refContext.isNotEmpty()) { appendLine(refContext.joinToString("\n\n")); appendLine() }
            append(text.replace(refPattern) { it.groupValues[1] })
        }
        setSelectedContext(null)

        if (!userAlreadyRendered) {
            renderUserMessage(finalText)
        }

        // Agentic Search / RAG
        // ═══ 多轮 Agentic Search 必须在后台线程执行，避免阻塞 EDT ═══
        if (settings.agenticSearchEnabled && isCodeQuery(text) && settings.agenticSearchMaxRounds > 1) {
            addMessageLabel(I18n.tr("chat.agent.searching"))
            ApplicationManager.getApplication().executeOnPooledThread {
                val engine = ToolUseEngine(project, settings.agenticSearchMaxRounds)
                val result = engine.execute(text, singleRound = false)
                ApplicationManager.getApplication().invokeLater {
                    if (result.toolCalls.isNotEmpty()) {
                        addMessageLabel(I18n.tr("chat.agent.search.complete") + " " + result.toolCalls.size + I18n.tr("chat.agent.search.tool.calls"))
                    }

                    if (!userAlreadyRendered) {
                        messageHistory.add(ChatMessage("user", finalText))
                    } else {
                        val lastIdx = messageHistory.size - 1
                        if (lastIdx >= 0 && messageHistory[lastIdx].role == "user") {
                            messageHistory[lastIdx] = ChatMessage("user", finalText)
                        } else {
                            messageHistory.add(ChatMessage("user", finalText))
                        }
                    }
                    messageHistory.add(ChatMessage("assistant", result.answer))
                    renderAssistantMessage(result.answer)
                    currentSession().lastActiveTime = System.currentTimeMillis()
                    saveSessions()
                    ensureMessagesFiller()
                    scrollToBottom()
                    sendStopButton.text = I18n.tr("chat.send.enter")
                    sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
                }
            }
            return
        }

        val enrichedText = if (settings.agenticSearchEnabled && isCodeQuery(text)) {
            val searchContext = buildCodeSearchContext(text)
            if (searchContext.isNotEmpty()) "以下是通过代码搜索找到的上下文：\n$searchContext\n\n根据以上项目代码上下文，回答以下问题：\n" + finalText else finalText
        } else {
            val projectContext = ragRetriever.retrieve(text, topN = 8)
            if (projectContext.isNotEmpty()) projectContext + "\n根据以上项目文档上下文，回答以下问题：\n" + finalText else finalText
        }

        if (!userAlreadyRendered) {
            messageHistory.add(ChatMessage("user", enrichedText))
        } else {
            val lastIdx = messageHistory.size - 1
            if (lastIdx >= 0 && messageHistory[lastIdx].role == "user") {
                messageHistory[lastIdx] = ChatMessage("user", enrichedText)
            } else {
                messageHistory.add(ChatMessage("user", enrichedText))
            }
        }
        currentSession().lastActiveTime = System.currentTimeMillis()
        saveSessions()

        removeMessagesFiller()
        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val streamTextArea = createStreamingArea(streamBubble)
        sendStopButton.text = I18n.tr("chat.stop.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")

        val onTokenBlock: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                if (thinkingTimer?.isRunning == true) { thinkingTimer?.stop(); thinkingTimer = null; streamTextArea.text = "" }
                streamTextArea.append(token); streamTextArea.revalidate(); messagesPanel.revalidate(); scrollToBottom()
            }
        }
        val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
                stopThinkingAnimation()
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) { oldState.eventSource.cancel(); removeStreamingArea(oldState) }
                messageHistory.add(ChatMessage("assistant", fullResponse)); renderAssistantMessage(fullResponse)
                usage?.let { currentSession().totalTokens += it.totalTokens; addMessageLabel("── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})") }
                saveSessions(); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()
            }
        }
        val onErrorBlock: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                stopThinkingAnimation()
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) { oldState.eventSource.cancel(); removeStreamingArea(oldState) }
                addMessageLabel(I18n.tr("chat.error.prefix") + " " + error.message); sendStopButton.text = I18n.tr("chat.send.enter"); sendStopButton.toolTipText = I18n.tr("chat.tooltip.send"); ensureMessagesFiller(); scrollToBottom()
            }
        }

        val qaSystemPrompt = buildString {
            appendLine(DOMAIN_RESTRICTION_PROMPT); appendLine()
            appendLine("你是一个 AI 代码助手，帮助用户解答技术问题。")
            appendLine(if (DeepSeekSettings.instance.language == "en") "Please reply in English." else "请用中文回复。")
            val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent()
            if (skillsContent.isNotBlank()) append(skillsContent)
        }

        val eventSource = client.chatStream(
            messages = listOf(ChatMessage("system", qaSystemPrompt)) + messageHistory.toList(),
            onToken = onTokenBlock, onComplete = onCompleteBlock, onError = onErrorBlock
        )
        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = streamBubble))
    }

    private fun sendAgentMessage(userText: String) {
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

        // Render user message and save to history
        renderUserMessage(finalText)
        messageHistory.add(ChatMessage("user", finalText))
        currentSession().lastActiveTime = System.currentTimeMillis()
        saveSessions()

        // Build project structure context
        val projectStructure = buildProjectStructure()

        // ── 静默搜索相关源文件，将其完整内容作为上下文 ──
        val relatedContext = buildRelatedFileContext(finalText)
        val hasRelatedContext = relatedContext.isNotEmpty()

        // Source roots hint
        val sourceRoots = getSourceRootPaths()
        val sourceRootsHint = if (sourceRoots.isNotEmpty()) {
            "项目的源码根目录有：\n" + sourceRoots.joinToString("\n") { "- $it" }
        } else ""

        // 获取技能内容（所有阶段共享）
        val skillsContent = unifiedSettingsPanel.getEnabledSkillsContent()

        // ════════════════════════════════════════════════════════════
        //  意图确认 Phase — 由 Agent Pipeline 配置决定
        // ════════════════════════════════════════════════════════════
        val p0Provider = LlmProviderRegistry.get(settings.agentPhase0Provider)
        val p0ApiKey = p0Provider.apiKey(settings)

        val planSystemPrompt = buildString {
            appendLine(DOMAIN_RESTRICTION_PROMPT)
            appendLine()
            appendLine("你是代码助手的规划 Agent（Planner）。你的任务是分析用户的需求，结合项目结构和相关源文件，制定详细的代码修改计划。")
            appendLine()
            appendLine("## 项目结构")
            appendLine(projectStructure)
            appendLine()
            if (hasRelatedContext) {
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

        val p1Provider = LlmProviderRegistry.get(settings.agentPhase1Provider)
        val p1Config = Pair(p1Provider.baseUrl(settings), p1Provider.apiKey(settings))
        val p1Model = settings.agentPhase1Model

        if (p0ApiKey.isNotBlank()) {
            // Phase 0 Provider 已配置 → 先进行意图确认
            startIntentConfirmation(
                userText = userText,
                projectStructure = projectStructure,
                relatedContext = relatedContext,
                planSystemPrompt = planSystemPrompt,
                finalText = finalText,
                sourceRootsHint = sourceRootsHint,
                skillsContent = skillsContent
            )
        } else {
            // Phase 0 未配置 → 直接进入规划阶段
            addMessageLabel(I18n.tr("chat.agent.planning.prefix") + p1Model + I18n.tr("chat.agent.planning.suffix"))
            startPlanPhase(
                planSystemPrompt = planSystemPrompt,
                finalText = finalText,
                projectStructure = projectStructure,
                relatedContext = relatedContext,
                sourceRootsHint = sourceRootsHint,
                skillsContent = skillsContent
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
        planSystemPrompt: String,
        finalText: String,
        sourceRootsHint: String,
        skillsContent: String,
        previousAnalysis: String? = null
    ) {
        val s = DeepSeekSettings.instance
        val p0Provider = LlmProviderRegistry.get(s.agentPhase0Provider)
        val phase0Config = Pair(p0Provider.baseUrl(s), p0Provider.apiKey(s))
        val phase0Model = s.agentPhase0Model

        addMessageLabel("🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.understanding") + "...")

        val animChars = "◐◓◑◒"
        val analysisLabel = JBTextArea("🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.understanding") + " ◐").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 12)
            foreground = JBColor(0x666666, 0x999999)
            background = messagesPanel.background
            margin = JBUI.insets(4, 8, 4, 8)
            border = JBUI.Borders.empty()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val analysisLabelAdded = messagesPanel.componentCount
        messagesPanel.add(analysisLabel, fillWidthConstraints)
        messagesPanel.add(Box.createVerticalStrut(2), fillWidthConstraints)
        revalidateAndScroll()

        val animTimer = Timer(300) {
            val idx = (System.currentTimeMillis() / 300).toInt() % 4
            analysisLabel.text = "🤔 " + p0Provider.displayName +  I18n.tr("chat.agent.understanding") + " ${animChars[idx]}"
        }
        animTimer.start()

        val analysisPrompt = buildString {
            appendLine(DOMAIN_RESTRICTION_PROMPT)
            appendLine()
            appendLine("你是一个需求分析助手。分析用户对代码库的需求，用中文一句话概括用户的核心意图（不超过50字）。")
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
                appendLine("请结合补充信息重新分析，用中文一句话概括（不超过50字）。")
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
                maxTokens = 256,
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
                    val cleanInterpretation = interpretation.trim().removePrefix("分析：").removePrefix("分析结果：").trim()
                    addMessageLabel(I18n.tr("chat.agent.interprets") + " " + p0Provider.displayName + " " + cleanInterpretation)

                    // ── 确认按钮 ──
                    val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                        isOpaque = false
                        maximumSize = Dimension(Short.MAX_VALUE.toInt(), 40)
                    }

                    val p1ProviderLocal = LlmProviderRegistry.get(s.agentPhase1Provider)
                    val p1ConfigLocal = Pair(p1ProviderLocal.baseUrl(s), p1ProviderLocal.apiKey(s))
                    val p1ModelLocal = s.agentPhase1Model

                    val yesBtn = JButton(I18n.tr("chat.yes.this.is.what.i.mean")).apply {
                        addActionListener {
                            messagesPanel.remove(buttonPanel)
                            revalidateAndScroll()
                            // 进入规划阶段
                            addMessageLabel(I18n.tr("chat.agent.planning.prefix") + p1ModelLocal + I18n.tr("chat.agent.planning.suffix"))
                            startPlanPhase(
                                planSystemPrompt = planSystemPrompt,
                                finalText = finalText,
                                projectStructure = projectStructure,
                                relatedContext = relatedContext,
                                sourceRootsHint = sourceRootsHint,
                                skillsContent = skillsContent
                            )
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
                                    planSystemPrompt = planSystemPrompt,
                                    finalText = finalText,
                                    sourceRootsHint = sourceRootsHint,
                                    skillsContent = skillsContent,
                                    previousAnalysis = cleanInterpretation
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
                    addMessageLabel(I18n.tr("chat.phase0.failed") + " " + error.message + I18n.tr("chat.phase0.failed.suffix"))
                    val p1ProviderErr = LlmProviderRegistry.get(s.agentPhase1Provider)
                    val p1ModelErr = s.agentPhase1Model
                    addMessageLabel(I18n.tr("chat.agent.planning.prefix") + p1ModelErr + I18n.tr("chat.agent.planning.suffix"))
                    startPlanPhase(
                        planSystemPrompt = planSystemPrompt,
                        finalText = finalText,
                        projectStructure = projectStructure,
                        relatedContext = relatedContext,
                        sourceRootsHint = sourceRootsHint,
                        skillsContent = skillsContent
                    )
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 1: 规划 Agent — DeepSeek-V4-Pro
    // ════════════════════════════════════════════════════════════════

    private fun startPlanPhase(
        planSystemPrompt: String,
        finalText: String,
        projectStructure: String,
        relatedContext: String,
        sourceRootsHint: String,
        skillsContent: String
    ) {
        val s = DeepSeekSettings.instance
        val p1Provider = LlmProviderRegistry.get(s.agentPhase1Provider)
        val p1Config = Pair(p1Provider.baseUrl(s), p1Provider.apiKey(s))
        val p1Model = s.agentPhase1Model

        removeMessagesFiller()
        val planBubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val planTextArea = createStreamingArea(planBubble)
        sendStopButton.text = I18n.tr("chat.stop.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")

        val onToken: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                planTextArea.append(token)
                messagesPanel.validate()
                scrollToBottom()
            }
        }
        val onComplete: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
                if (chatState.get() is ChatState.Streaming)
                    cleanupStreamingAndResetButton()
                // 保存规划结果
                messageHistory.add(ChatMessage("assistant", fullResponse))
                renderAssistantMessage(fullResponse)
                usage?.let { currentSession().totalTokens += it.totalTokens }

                // ── 过渡到 Phase 2 ──
                val p2Provider = LlmProviderRegistry.get(s.agentPhase2Provider)
                val p2Config = Pair(p2Provider.baseUrl(s), p2Provider.apiKey(s))
                val p2Model = s.agentPhase2Model
                addMessageLabel(I18n.tr("chat.agent.coding.prefix") + p2Model + I18n.tr("chat.agent.coding.suffix"))
                startCodePhase(p2Config, p2Model, fullResponse, projectStructure,
                    relatedContext, sourceRootsHint, skillsContent, finalText)
            }
        }
        val onError: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                if (chatState.get() is ChatState.Streaming)
                    cleanupStreamingAndResetButton()
                addMessageLabel(I18n.tr("chat.planning.agent.error") + " " + error.message)
                ensureMessagesFiller(); scrollToBottom()
            }
        }

        val eventSource = client.chatStreamWithExplicitConfig(
            baseUrl = p1Config.first, apiKey = p1Config.second,
            model = p1Model, temperature = 0.7, maxTokens = 4096,
            messages = listOf(ChatMessage("system", planSystemPrompt), ChatMessage("user", finalText)),
            onToken = onToken, onComplete = onComplete, onError = onError
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
        finalText: String
    ) {
        removeMessagesFiller()
        val codeBubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val codeTextArea = createStreamingArea(codeBubble)
        sendStopButton.text = I18n.tr("chat.stop.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.stop")

        val systemPrompt = buildString {
            appendLine(DOMAIN_RESTRICTION_PROMPT)
            appendLine()
            appendLine("你是代码助手的编码 Agent（Coder）。你的任务是根据规划 Agent 制定的计划，生成具体的代码修改。")
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
            appendLine("## 规划 Agent 的计划")
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

        val onToken: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                codeTextArea.append(token)
                messagesPanel.validate()
                scrollToBottom()
            }
        }
        val onComplete: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
                if (chatState.get() is ChatState.Streaming)
                    cleanupStreamingAndResetButton()
                messageHistory.add(ChatMessage("assistant", fullResponse))
                renderAssistantMessage(fullResponse)
                usage?.let { currentSession().totalTokens += it.totalTokens }

                // 解析并执行文件操作
                val operations = parseFileOperations(fullResponse)
                if (operations.isNotEmpty()) {
                    applyFileOperations(operations, planResponse)
                }

                // ── 过渡到 Phase 3 ──
                val s = DeepSeekSettings.instance
                val p3Provider = LlmProviderRegistry.get(s.agentPhase3Provider)
                val p3ApiKey = p3Provider.apiKey(s)
                if (p3ApiKey.isNotBlank()) {
                    val p3Config = Pair(p3Provider.baseUrl(s), p3ApiKey)
                    val p3Model = s.agentPhase3Model
                    addMessageLabel(I18n.tr("chat.agent.reviewing.prefix") + p3Model + I18n.tr("chat.agent.reviewing.suffix"))
                    startReviewPhase(p3Config, p3Model, planResponse, fullResponse)
                } else {
                    addMessageLabel(I18n.tr("chat.info.skip.review"))
                    finalizeAgentSession()
                }
            }
        }
        val onError: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                if (chatState.get() is ChatState.Streaming)
                    cleanupStreamingAndResetButton()
                addMessageLabel(I18n.tr("chat.coding.agent.error") + " " + error.message)
                ensureMessagesFiller(); scrollToBottom()
            }
        }

        val eventSource = client.chatStreamWithExplicitConfig(
            baseUrl = p2Config.first, apiKey = p2Config.second,
            model = p2Model, temperature = 0.7, maxTokens = 8192,
            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", "请根据上面的规划生成代码。")),
            onToken = onToken, onComplete = onComplete, onError = onError
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

        val onToken: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                reviewTextArea.append(token)
                messagesPanel.validate()
                scrollToBottom()
            }
        }
        val onComplete: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
                if (chatState.get() is ChatState.Streaming)
                    cleanupStreamingAndResetButton()
                messageHistory.add(ChatMessage("assistant", fullResponse))
                renderAssistantMessage(fullResponse)
                usage?.let {
                    currentSession().totalTokens += it.totalTokens
                    addMessageLabel("── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})")
                }
                finalizeAgentSession()
            }
        }
        val onError: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                if (chatState.get() is ChatState.Streaming)
                    cleanupStreamingAndResetButton()
                addMessageLabel(I18n.tr("chat.review.agent.error") + " " + error.message)
                finalizeAgentSession()
            }
        }

        val eventSource = client.chatStreamWithExplicitConfig(
            baseUrl = reviewConfig.first, apiKey = reviewConfig.second,
            model = reviewModel, temperature = 0.3, maxTokens = 2048,
            messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", "请审查上述代码修改。")),
            onToken = onToken, onComplete = onComplete, onError = onError
        )
        chatState.set(ChatState.Streaming(eventSource = eventSource, bubble = reviewBubble))
    }

    /** 清理当前流式状态并重置发送按钮。 */
    private fun cleanupStreamingAndResetButton() {
        val oldState = chatState.getAndSet(ChatState.Idle)
        if (oldState is ChatState.Streaming) {
            oldState.eventSource.cancel()
            removeStreamingArea(oldState)
        }
        sendStopButton.text = I18n.tr("chat.send.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
    }

    /** 完成 Agent 会话的收尾工作（保存、填充）。 */
    private fun finalizeAgentSession() {
        saveSessions()
        sendStopButton.text = I18n.tr("chat.send.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
        ensureMessagesFiller()
        scrollToBottom()
    }

    private data class FileOperation(
        val path: String,
        val content: String,
        val action: String // "write" or "delete"
    )

    /**
     * Parse the AI response to extract file operations.
     * Format: <file path="..."> ...content... </file>
     */
    private fun parseFileOperations(response: String): List<FileOperation> {
        val operations = mutableListOf<FileOperation>()

        // 正则1：带 ```代码围栏``` 的标准格式（兼容 CRLF、多余空白）
        //   <file path="...">
        //   ```lang
        //   内容
        //   ```
        //   </file>
        val fileRegex = Regex(
            """<file\s+path="([^"]+)"(?:\s+action="([^"]*)")?\s*>(?:\r?\n)?\s*```(\w+)?\s*(?:\r?\n)?\s*([\s\S]*?)\s*```\s*(?:\r?\n)?\s*</file>""",
            RegexOption.MULTILINE
        )
        for (match in fileRegex.findAll(response)) {
            val path = match.groupValues[1].trim()
            val action = match.groupValues[2].ifBlank { "write" }
            val content = match.groupValues[4].trim()
            operations.add(FileOperation(path, content, action))
        }

        // 正则2：不带 ```围栏``` 的简洁格式（纯文本/配置文件）
        //   <file path="...">内容</file>
        val simpleFileRegex = Regex(
            """<file\s+path="([^"]+)"(?:\s+action="([^"]*)")?\s*>([\s\S]*?)</file>""",
            RegexOption.MULTILINE
        )
        for (match in simpleFileRegex.findAll(response)) {
            val path = match.groupValues[1].trim()
            val action = match.groupValues[2].ifBlank { "write" }
            val content = match.groupValues[3].trim()
            if (operations.none { it.path == path }) {
                operations.add(FileOperation(path, content, action))
            }
        }

        // 调试日志：如果响应中有 <file> 但没匹配上，输出片段辅助排查
        if (operations.isEmpty() && response.contains("<file")) {
            System.err.println("[Agent] parseFileOperations: found <file> tags but no regex matched. Response snippet: ${response.take(500)}")
        } else if (operations.isNotEmpty()) {
            System.err.println("[Agent] parseFileOperations: ${operations.size} operations parsed")
        }

        return operations
    }

    /**
     * Apply file operations to the project using IntelliJ's virtual file system.
     * Performs path traversal validation and user confirmation.
     */
    private fun applyFileOperations(operations: List<FileOperation>, planResponse: String? = null) {
        // Reset cached paths for fresh scan
        cachedSourceRoots = null

        val projectBasePath = project.basePath ?: return
        val projectDir = java.io.File(projectBasePath).canonicalPath

        // Filter out paths that escape the project directory
        val safeOps = operations.filter { op ->
            val targetFile = java.io.File(projectBasePath, op.path)
            val canonicalPath = try {
                targetFile.canonicalPath
            } catch (_: Exception) {
                null
            }
            if (canonicalPath == null || !canonicalPath.startsWith(projectDir)) {
                addMessageLabel(I18n.tr("chat.skip.unsafe.path") + " " + op.path)
                false
            } else {
                true
            }
        }

        if (safeOps.isEmpty()) {
            addMessageLabel(I18n.tr("chat.no.safe.operations"))
            return
        }

        // Build confirmation message
        val summaryLines = mutableListOf<String>()
        var writeCount = 0
        var deleteCount = 0
        for (op in safeOps) {
            when (op.action) {
                "delete" -> {
                    val f = java.io.File(projectBasePath, op.path)
                    if (f.exists()) {
                        summaryLines.add(I18n.tr("chat.delete.prefix") + " " + op.path)
                        deleteCount++
                    }
                }
                else -> {
                    val f = java.io.File(projectBasePath, op.path)
                    if (f.exists()) {
                        summaryLines.add(I18n.tr("chat.modify.prefix") + " " + op.path)
                    } else {
                        summaryLines.add(I18n.tr("chat.create.prefix") + " " + op.path)
                    }
                    writeCount++
                }
            }
        }
        val confirmMsg = I18n.tr("chat.confirm.file.operations.header") + "\n\n" + summaryLines.joinToString("\n") +
                "\n\n" + I18n.tr("chat.confirm.execute.question")

        val confirmed = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            confirmMsg,
            I18n.tr("chat.confirm.agent.action"),
            I18n.tr("chat.confirm.execute"),
            I18n.tr("chat.cancel"),
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (confirmed != com.intellij.openapi.ui.Messages.YES) {
            addMessageLabel(I18n.tr("chat.agent.cancelled"))
            return
        }

        // 收集结果和文件变更，在 WriteCommandAction 外部渲染 UI
        var resultMsg = ""
        var hasModifiedFiles = false

        WriteCommandAction.runWriteCommandAction(project) {
            var created = 0
            var modified = 0
            var deleted = 0
            val fileChanges = mutableListOf<FileChangeInfo>()

            for (op in safeOps) {
                val targetFile = java.io.File(projectBasePath, op.path)

                when (op.action) {
                    "delete" -> {
                        val vf = LocalFileSystem.getInstance().findFileByIoFile(targetFile)
                        if (vf != null && vf.exists()) {
                            try {
                                vf.delete(this)
                                deleted++
                            } catch (e: Exception) {
                                System.err.println("[Agent] delete failed: ${op.path} - ${e.message}")
                            }
                        }
                    }
                    else -> {
                        val resolvedFile = resolveToExistingSourceRoot(targetFile)
                        val effectiveFile = resolvedFile ?: targetFile
                        val effectiveParent = effectiveFile.parentFile

                        if (resolvedFile == null && effectiveParent != null) {
                            val existingAncestor = findExistingAncestor(effectiveParent)
                            if (existingAncestor == null) {
                                System.err.println("[Agent] path ${op.path} does not match any existing source root")
                            }
                        }

                        try {
                            effectiveParent?.mkdirs()
                        } catch (e: Exception) {
                            System.err.println("[Agent] mkdirs failed for ${effectiveParent}: ${e.message}")
                        }

                        val contentBytes = op.content.toByteArray(Charsets.UTF_8)
                        val isNew = !effectiveFile.exists()

                        if (isNew) {
                            // Create new file
                            val parentVFile = LocalFileSystem.getInstance()
                                .findFileByIoFile(effectiveFile.parentFile)
                            if (parentVFile != null && parentVFile.exists()) {
                                try {
                                    val vf = parentVFile.createChildData(this, effectiveFile.name)
                                    vf.setBinaryContent(contentBytes)
                                    created++
                                    fileChanges.add(FileChangeInfo(effectiveFile.absolutePath, ByteArray(0), isNew = true))
                                } catch (e: Exception) {
                                    System.err.println("[Agent] VFS create failed for ${op.path}: ${e.message}")
                                    // fallback to java.io.File
                                    effectiveFile.writeBytes(contentBytes)
                                    LocalFileSystem.getInstance().refreshIoFiles(listOf(effectiveFile))
                                    created++
                                    fileChanges.add(FileChangeInfo(effectiveFile.absolutePath, ByteArray(0), isNew = true))
                                }
                            } else {
                                effectiveFile.writeBytes(contentBytes)
                                LocalFileSystem.getInstance().refreshIoFiles(listOf(effectiveFile))
                                created++
                                fileChanges.add(FileChangeInfo(effectiveFile.absolutePath, ByteArray(0), isNew = true))
                            }
                        } else {
                            // Update existing file — 先将原始内容读入内存备份，再覆盖写入
                            try {
                                val originalBytes = effectiveFile.readBytes()
                                fileChanges.add(FileChangeInfo(effectiveFile.absolutePath, originalBytes))
                            } catch (e: Exception) {
                                System.err.println("[Agent] backup (in-memory) failed for ${op.path}: ${e.message}")
                                // 备份失败不影响继续写入
                            }

                            try {
                                val vf = LocalFileSystem.getInstance().findFileByIoFile(effectiveFile)
                                if (vf != null && vf.exists()) {
                                    vf.setBinaryContent(contentBytes)
                                    modified++
                                } else {
                                    effectiveFile.writeBytes(contentBytes)
                                    LocalFileSystem.getInstance().refreshIoFiles(listOf(effectiveFile))
                                    modified++
                                }
                            } catch (e: Exception) {
                                System.err.println("[Agent] write failed for ${op.path}: ${e.message}")
                                // 尝试 fallback
                                try {
                                    effectiveFile.writeBytes(contentBytes)
                                    LocalFileSystem.getInstance().refreshIoFiles(listOf(effectiveFile))
                                    modified++
                                } catch (e2: Exception) {
                                    System.err.println("[Agent] fallback write also failed for ${op.path}: ${e2.message}")
                                }
                            }
                        }
                    }
                }
            }

            // Refresh project view
            val projectVDir = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(projectBasePath))
            if (projectVDir != null) {
                projectVDir.refresh(false, true)
            }

            // 在 WriteCommandAction 内构建结果消息字符串（UI操作移到外面）
            resultMsg = buildString {
                appendLine(I18n.tr("chat.agent.complete"))
                if (created > 0) appendLine(I18n.tr("chat.created.files") + " " + created + I18n.tr("chat.count.suffix"))
                if (modified > 0) appendLine(I18n.tr("chat.modified.files") + " " + modified + I18n.tr("chat.count.suffix"))
                if (deleted > 0) appendLine(I18n.tr("chat.deleted.files") + " " + deleted + I18n.tr("chat.count.suffix"))
                if (created == 0 && modified == 0 && deleted == 0) {
                    appendLine("- (没有文件操作被应用)")
                }
            }
            if (fileChanges.isNotEmpty()) {
                // 从规划 Agent 的计划文本中提取语义化标题，概括本次变更内容
                val semanticTitle = planResponse?.let { plan ->
                    val skipPatterns = listOf(
                        "输出格式", "项目结构", "相关源文件", "源码根目录",
                        "分析", "计划", "注意事项", "依赖关系", "规则"
                    )
                    plan.lines().firstOrNull { line ->
                        val trimmed = line.trim()
                        val heading = trimmed.replace(Regex("^#+\\s*"), "").trim()
                        trimmed.startsWith("#") && heading.isNotBlank() &&
                                skipPatterns.none { heading.contains(it) }
                    }?.replace(Regex("^#+\\s*"), "")?.trim()
                }
                val summary = if (!semanticTitle.isNullOrBlank()) {
                    semanticTitle
                } else {
                    // 回退：从实际文件操作生成摘要
                    val allPaths = safeOps.map { it.path.substringAfterLast("/").substringAfterLast("\\") }
                    val totalChanged = created + modified + deleted
                    if (totalChanged <= 2) {
                        allPaths.joinToString("、")
                    } else {
                        buildString {
                            if (created > 0) append("新建 ${created} 个文件")
                            if (modified > 0) { if (isNotEmpty()) append("，"); append("修改 ${modified} 个文件") }
                            if (deleted > 0) { if (isNotEmpty()) append("，"); append("删除 ${deleted} 个文件") }
                        }
                    }
                }
                val record = ChangeRecord(
                    title = I18n.tr("chat.change.title.prefix") + " ${summary.ifEmpty { I18n.tr("chat.unknown") }}",
                    changes = fileChanges
                )
                project.getService(ChangeManagementStore::class.java).addRecord(record)
                hasModifiedFiles = true
            }
        }

        // 在 WriteCommandAction 外部渲染 UI 结果
        addMessageLabel(resultMsg)
        if (hasModifiedFiles) {
            addMessageLabel(I18n.tr("chat.change.recorded"))
        }
    }

    // ===== Smart path resolution helpers =====

    /** Cache of project source root paths, computed once per operation */
    private var cachedSourceRoots: List<java.io.File>? = null

    /**
     * Try to resolve a target file path to an existing source root.
     * If the AI says "src/main/java/com/example/X.java" but the project uses
     * "src/main/kotlin/com/example/", this remaps to the existing kotlin dir.
     *
     * Returns null if no remapping is needed (path already valid).
     */
    private fun resolveToExistingSourceRoot(targetFile: java.io.File): java.io.File? {
        val basePath = project.basePath ?: return null
        val baseDir = java.io.File(basePath)

        // If the file already exists, use it as-is
        if (targetFile.exists()) return null

        // Get the package path (everything after the source root segment)
        val relativePath = try {
            targetFile.canonicalPath.removePrefix(baseDir.canonicalPath).trimStart('/').replace('\\', '/')
        } catch (_: Exception) { return null }

        if (relativePath.isEmpty()) return null

        val sourceRoots = getSourceRootPaths()

        // Check if the path already starts with an existing source root
        for (root in sourceRoots) {
            if (relativePath.startsWith(root.trimStart('/'))) {
                return null // Already matches an existing root — use as-is
            }
        }

        // Extract the package+filename part (e.g., "com/example/X.java" from
        // "src/main/java/com/example/X.java" or "kotlin/com/example/X.java")
        val sourceRootPatterns = listOf(
            "src/main/java/", "src/main/kotlin/", "src/main/resources/",
            "src/test/java/", "src/test/kotlin/", "src/test/resources/",
            "java/", "kotlin/", "resources/"
        )
        val packagePart = sourceRootPatterns
            .firstOrNull { relativePath.contains(it) }
            ?.let { relativePath.substringAfter(it) }
            ?: relativePath

        // Find the best matching source root by checking if a directory at
        // that root + packagePart already exists
        for (root in sourceRoots) {
            val candidate = java.io.File(root, packagePart)
            if (candidate.parentFile?.exists() == true) {
                return candidate
            }
            // Also try finding any file with the same name in the package tree
            val existingFile = findFileByNameInProject(candidate.name)
            if (existingFile != null && existingFile.parentFile?.exists() == true) {
                // Same filename exists — use that directory for the new file
                return java.io.File(existingFile.parentFile, candidate.name)
            }
        }

        return null
    }

    /**
     * Walk up the parent chain to find the deepest ancestor that already exists.
     * Returns the lowest existing dir (the one closest to the leaf).
     */
    private fun findExistingAncestor(file: java.io.File): java.io.File? {
        var current = file
        while (current != null) {
            if (current.exists()) return current
            current = current.parentFile
        }
        return null
    }

    /**
     * Collect all source root and content root paths from the project.
     * This tells us where the project actually puts its source files.
     */
    private fun getSourceRootPaths(): List<String> {
        if (cachedSourceRoots != null) {
            return cachedSourceRoots!!.map { it.canonicalPath.replace('\\', '/') }
        }

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

        cachedSourceRoots = roots.map { java.io.File(it) }
        return roots
    }

    /**
     * Search the entire project for a file with the given name.
     * Used to find existing locations for files the AI wants to create.
     */
    private fun findFileByNameInProject(fileName: String): java.io.File? {
        val basePath = project.basePath ?: return null
        val baseDir = java.io.File(basePath)
        val results = mutableListOf<java.io.File>()

        try {
            baseDir.walkTopDown()
                .maxDepth(20)
                .filter { it.isFile && it.name == fileName }
                .forEach { results.add(it) }
        } catch (_: Exception) { }

        return results.firstOrNull()
    }

    private fun stopThinkingAnimation() {
        thinkingTimer?.stop()
        thinkingTimer = null
    }
    private fun stopStreaming() {
        stopThinkingAnimation()
        val oldState = chatState.getAndSet(ChatState.Idle)
        if (oldState is ChatState.Streaming) {
            oldState.eventSource.cancel()
            removeStreamingArea(oldState)
        }
        sendStopButton.text = I18n.tr("chat.send.enter")
        sendStopButton.toolTipText = I18n.tr("chat.tooltip.send")
        ensureMessagesFiller()
    }

    private fun saveSessions() {
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

        for (kw in keywords.take(5)) {
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
    private fun buildRelatedFileContext(userText: String): String {
        val keywords = extractSearchKeywords(userText)
        if (keywords.isEmpty()) return ""

        val seen = mutableSetOf<String>()
        val sb = StringBuilder()
        val projectBase = project.basePath ?: return ""

        for (kw in keywords.take(5)) {
            if (kw in seen) continue
            seen.add(kw)

            val result = agenticSearch.grep(kw)
            if (result.matches.isEmpty()) continue

            val byFile = result.matches.groupBy { it.filePath }
            for ((filePath, matches) in byFile.entries.take(3)) {
                if (sb.count { it == '\n' } > 300) return sb.toString()
                val file = java.io.File(filePath)
                if (!file.exists() || !file.isFile) continue
                val content = try {
                    file.readText(Charsets.UTF_8)
                } catch (_: Exception) { continue }
                if (content.length > 15000) continue
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

    private fun scrollToBottom() {
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
    private fun addMessageLabel(text: String) {
        showMessages()
        val label = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 12)
            foreground = JBColor(0x666666, 0x999999)
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
    private fun renderUserMessage(content: String) {
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
    private fun renderAssistantMessage(content: String) {
        showMessages()
        val bubble = MessageBubble(project, MessageBubble.Role.ASSISTANT, content)
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
    private fun createStreamingArea(bubble: MessageBubble): JBTextArea {
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
    private fun removeStreamingArea(state: ChatState.Streaming) {
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

    private fun revalidateAndScroll() {
        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollToBottom()
    }

    /**
     * Remove the vertical filler from messagesPanel, so messages take their
     * natural height and the scroll pane viewport expands gradually as content
     * streams in (little-by-little expansion).
     */
    private fun removeMessagesFiller() {
        messagesPanel.remove(verticalFiller)
    }

    /**
     * Ensure the vertical filler is present at the end of messagesPanel.
     * The filler has weighty=1.0 so it expands to take any extra vertical space,
     * pushing messages to the top and eliminating blank space below.
     */
    private fun ensureMessagesFiller() {
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
}