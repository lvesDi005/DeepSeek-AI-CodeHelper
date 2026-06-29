package com.deepseek.plugin.chat

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.DeepSeekPluginException
import com.deepseek.plugin.api.StepFunApiClient
import com.deepseek.plugin.api.Usage
import com.deepseek.plugin.chat.ChatState
import com.deepseek.plugin.context.ProjectContextProvider
import com.deepseek.plugin.store.SessionStore
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.ui.AttachedFile
import com.deepseek.plugin.ui.ChatInputBar
import com.deepseek.plugin.ui.ChatToolbar
import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.FileAttachmentPreview
import com.deepseek.plugin.ui.HistoryDialog
import com.deepseek.plugin.ui.MessageBubble
import com.deepseek.plugin.ui.ResponseSegment
import com.deepseek.plugin.ui.SelectedCodePreview
import com.deepseek.plugin.ui.SessionBar
import com.deepseek.plugin.ui.TranslateDialog
import com.deepseek.plugin.ui.UsageDialog
import com.deepseek.plugin.ui.WelcomePanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
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
import java.awt.event.*
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

class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        /** 当前活动的 ChatPanel 实例，供外部 Action 向聊天面板推送内容 */
        @JvmStatic
        var currentInstance: ChatPanel? = null
            private set
    }

    private val client = DeepSeekApiClient()
    private val stepFunClient = StepFunApiClient()
    private val contextProvider = ProjectContextProvider(project)
    private val sessionStore = SessionStore(project.basePath)
    private val sessions = mutableListOf<ChatSession>()
    private var currentSessionIndex = 0
    /** 线程安全的聊天状态机 — 替代 isStreaming + currentEventSource + streamBuffer + streamingBubble + … */
    private val chatState = AtomicReference<ChatState>(ChatState.Idle)
    private var sessionCounter = 1
    private var currentMode = ChatMode.Q_A

    /** P3: 虚拟化滚动—当前会话中可见的第一条消息索引（从尾部算） */
    private var visibleStartIndex = 0
    /** P3: 虚拟化滚动—每个批次渲染的最大消息数 */
    private val VISIBLE_BATCH_SIZE = 30

    /** 防抖保存定时器：500ms 内多次调用只触发一次磁盘写入 */
    private val saveTimer = Timer(500) {
        SwingUtilities.invokeLater { doSaveSessions() }
    }.apply { isRepeats = false }

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

    private val inputArea = AutoResizingTextArea(4, 0, project, { sendMessage() }, { isStreaming })

    // ── Combined send/stop button ──

    private val sendStopButton = JButton("\u25B6 发送 (Enter)").apply {
        toolTipText = "发送消息"
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
            sessions.add(ChatSession("会话 1"))
            sessionComboBox.addItem("会话 1")
        }
        sessionComboBox.addActionListener {
            val idx = sessionComboBox.selectedIndex
            if (idx >= 0 && idx < sessions.size && idx != currentSessionIndex) {
                switchToSession(idx)
            }
        }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(ChatToolbar(
                onShowUsage = { showUsageDialog() },
                onShowHistory = { showHistoryDialog() }
            ))
            add(SessionBar(
                sessionComboBox = sessionComboBox,
                onNewSession = { createNewSession() },
                onClearAll = { clearAllSessions() },
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
            ))
            add(JSeparator())
        }

        // Wrap messages container — anchor dots are embedded per-message
        val messagesWithNav = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(messageContainer, BorderLayout.CENTER)
        }

        // Create splitPane first so ChatInputBar can reference it in the resize callback
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = messagesWithNav
            resizeWeight = 0.75
            dividerSize = 3
            isContinuousLayout = true
        }

        val bottomPanel = ChatInputBar(
            inputScrollPane = JBScrollPane(inputArea),
            selectedCodePanel = selectedCodePreviewPanel,
            fileAttachmentPanel = fileAttachmentPanel,
            modeSelector = createModeDropdown(),
            uploadButton = createUploadButton(),
            translateButton = createTranslateButton(),
            sendStopButton = sendStopButton,
            onResizeRequest = { deltaY ->
                val newLoc = splitPane.dividerLocation + deltaY
                splitPane.dividerLocation = newLoc.coerceIn(
                    splitPane.minimumDividerLocation,
                    splitPane.maximumDividerLocation
                )
            }
        )
        splitPane.bottomComponent = bottomPanel

        add(topPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)

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

    private fun createUploadButton(): JComponent {
        return createSmallRoundButton(AllIcons.Actions.Upload, "上传文件") {
            openFileChooser()
        }
    }

    private fun createTranslateButton(): JComponent {
        return createSmallRoundButton(AllIcons.Actions.Preview, "翻译") {
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
            dialogTitle = "选择要上传的文件"
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
     * Build the file context string from all attached files and clear them.
     * Text files are read directly; image files are parsed via StepFun API
     * with a modal progress dialog to prevent UI freezing.
     */
    private fun buildFileContext(): String {
        if (attachedFiles.isEmpty()) return ""

        val sb = StringBuilder()

        // Separate image files from text files
        val imageFiles = attachedFiles.filter { isImageFile(it.name) }
        val textFiles = attachedFiles.filter { !isImageFile(it.name) }

        // Process text files as before (fast, synchronous)
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

        // Process image files via StepFun API
        if (imageFiles.isNotEmpty()) {
            val settings = DeepSeekSettings.instance
            if (settings.stepFunApiKey.isBlank()) {
                // StepFun not configured — warn and skip
                sb.appendLine("[注意: StepFun API Key 未配置，已跳过 ${imageFiles.size} 张图片的解析]")
                sb.appendLine("[请在 Settings → Tools → DeepSeek AI 的 StepFun Image Parsing 中配置]")
                for (file in imageFiles) {
                    sb.appendLine("[图片: ${file.name} (未解析)]")
                }
            } else {
                // Parse images with a modal progress dialog (avoids EDT freeze)
                val imageResults = parseImagesWithProgress(imageFiles)
                for ((name, description) in imageResults) {
                    sb.appendLine("以下为图片 `${name}` 的解析结果：")
                    sb.appendLine("> $description")
                    sb.appendLine()
                }
            }
        }

        attachedFiles.clear()
        refreshFileAttachmentPanel()
        return sb.toString()
    }

    /**
     * Parse image files via StepFun API in a background thread with a modal
     * progress dialog, keeping the IDE responsive.
     *
     * When user clicks Cancel, a watchdog thread forcibly aborts the in-flight
     * OkHttp call via [StepFunApiClient.cancelCurrentCall], so the blocked
     * HTTP request is unblocked immediately instead of waiting for timeout.
     */
    private fun parseImagesWithProgress(imageFiles: List<AttachedFile>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val wasCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable {
                val indicator = ProgressManager.getInstance().progressIndicator
                val total = imageFiles.size

                for ((index, file) in imageFiles.withIndex()) {
                    // Check cancellation BEFORE starting this image
                    if (indicator != null && indicator.isCanceled) {
                        wasCancelled.set(true)
                        return@Runnable
                    }

                    // Update progress
                    indicator?.text = "正在解析图片 (${index + 1}/$total): ${file.name}"
                    indicator?.fraction = if (total > 1) (index.toDouble() / (total - 1)) else 1.0
                    indicator?.isIndeterminate = false

                    // Start a daemon watchdog thread that polls the indicator
                    // and cancels the OkHttp call if the user clicks Cancel.
                    // This is needed because call.execute() blocks the thread
                    // and cannot check isCanceled on its own.
                    val watchdog = Thread {
                        while (true) {
                            if (indicator != null && indicator.isCanceled) {
                                stepFunClient.cancelCurrentCall()
                                return@Thread
                            }
                            try { Thread.sleep(300) } catch (_: InterruptedException) { return@Thread }
                        }
                    }.apply { isDaemon = true }

                    watchdog.start()
                    val result = stepFunClient.parseImage(file.absolutePath)
                    watchdog.interrupt() // stop the watchdog, the call is done

                    // Check cancellation AFTER the call returns
                    if (indicator != null && indicator.isCanceled) {
                        wasCancelled.set(true)
                        return@Runnable
                    }

                    val description = result.getOrElse { e -> "[图片解析失败: ${e.message}]" }
                    results.add(file.name to description)
                }
            },
            "StepFun 图片解析",
            true,
            project
        )

        return if (wasCancelled.get()) {
            addMessageLabel("⏸️ 图片解析已取消")
            emptyList()
        } else {
            results
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
        stopStreaming()
        sessionCounter++
        val name = "会话 $sessionCounter"
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
            "确定要清除所有会话吗？此操作不可撤销。",
            "清除所有会话",
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
            val loadMoreBtn = JButton("▲ 加载更早消息 (${start} 条)").apply {
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

    // ===== Usage dialog =====

    private fun showUsageDialog() {
        UsageDialog(project, sessions, currentSessionIndex).show()
    }

    // ===== History dialog =====

    private fun showHistoryDialog() {
        val dialog = HistoryDialog(project, sessions, currentSessionIndex) { index ->
            switchToSession(index)
        }
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
        val combo = JComboBox(arrayOf("\uD83D\uDCAC 问答", "\uD83E\uDD16 Agent")).apply {
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

        if (currentMode == ChatMode.AGENT) {
            sendAgentMessage(text)
            return
        }

        val settings = DeepSeekSettings.instance
        if (settings.apiKey.isBlank()) {
            addMessageLabel("[ERROR] 请在 Settings → Tools → DeepSeek AI 配置 API Key.")
            return
        }

        inputArea.text = ""

        // Build user message: prepend file context + selected code context + @file references
        val fileContext = buildFileContext()
        val projectDir = project.basePath?.let { java.io.File(it) }
        val refPattern = Regex("@([\\w.\\-/]+)")
        val refs = refPattern.findAll(text).map { it.groupValues[1] }.toList()
        val refContext = refs.mapNotNull { refName ->
            projectDir?.let { dir ->
                val file = dir.resolve(refName)
                if (file.isFile && file.exists()) {
                    val content = file.readText().take(3000) // limit context size
                    "## @$refName\n```\n$content\n```"
                } else null
            }
        }
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
            if (refContext.isNotEmpty()) {
                appendLine(refContext.joinToString("\n\n"))
                appendLine()
            }
            // Replace @references in the display text with plain names
            append(text.replace(refPattern) { it.groupValues[1] })
        }
        // Clear the preview cards
        setSelectedContext(null)

        // Render user message
        renderUserMessage(finalText)

        // --- inject project context when user mentions class names ---
        val projectContext = contextProvider.getRelatedContext(text)
        val enrichedText = if (projectContext.isNotEmpty()) {
            projectContext + "\n根据以上项目代码上下文，回答以下问题：\n" + finalText
        } else finalText

        messageHistory.add(ChatMessage("user", enrichedText))
        currentSession().lastActiveTime = System.currentTimeMillis()
        saveSessions() // Save immediately so user messages are never lost

        // ── 准备流式渲染区域 ──
        removeMessagesFiller()
        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val streamTextArea = createStreamingArea(streamBubble)
        sendStopButton.text = "■ 停止"
        sendStopButton.toolTipText = "停止"

        // 先声明回调（引用 streamBubble 而非 eventSource），再启动流式请求
        val onTokenBlock: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                streamTextArea.append(token)
                scrollToBottom()
            }
        }

        val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
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
                sendStopButton.text = "▶ 发送 (Enter)"
                sendStopButton.toolTipText = "发送消息"
                ensureMessagesFiller()
                scrollToBottom()
            }
        }

        val onErrorBlock: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) {
                    oldState.eventSource.cancel()
                    removeStreamingArea(oldState)
                }

                val pluginErr = DeepSeekPluginException(
                    message = error.message ?: "API 调用失败",
                    cause = error,
                    userMessage = "⚠️ API 错误: ${error.message}"
                )
                addMessageLabel("[ERROR] ${pluginErr.userMessage}")
                sendStopButton.text = "▶ 发送 (Enter)"
                sendStopButton.toolTipText = "发送消息"
                ensureMessagesFiller()
                scrollToBottom()
            }
        }

        val eventSource = client.chatStream(
            messages = messageHistory.toList(),
            onToken = onTokenBlock,
            onComplete = onCompleteBlock,
            onError = onErrorBlock
        )

        // 设置为流式状态
        chatState.set(ChatState.Streaming(
            eventSource = eventSource,
            bubble = streamBubble
        ))
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

    private fun sendAgentMessage(userText: String) {
        val settings = DeepSeekSettings.instance
        if (settings.apiKey.isBlank()) {
            addMessageLabel("[ERROR] 请在 Settings → Tools → DeepSeek AI 配置 API Key.")
            return
        }

        inputArea.text = ""

        // Build file context and user message
        val fileContext = buildFileContext()
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

        // System prompt for agent mode (with current project structure)
        val sourceRoots = getSourceRootPaths()
        val sourceRootsHint = if (sourceRoots.isNotEmpty()) {
            "项目的源码根目录有：\n" + sourceRoots.joinToString("\n") { "- $it" }
        } else ""

        val systemPrompt = buildString {
            appendLine("你是 DeepSeek AI 代码助手，处于 Agent 模式。你的任务是理解用户的请求，自动分析当前项目结构，并对项目中的文件进行创建、修改或删除操作。")
            appendLine()
            appendLine("## 项目结构")
            appendLine(projectStructure)
            appendLine()
            if (sourceRootsHint.isNotEmpty()) {
                appendLine("## 项目源码根目录")
                appendLine(sourceRootsHint)
                appendLine()
                appendLine("**重要：创建新文件时必须使用上述已存在的源码根目录之一，不要创建新的源码根目录。**")
                appendLine()
            }
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
            appendLine("2. 对于已有的文件，如果需要修改，请包含完整的文件内容（不要省略）")
            appendLine("3. [重要] 创建新文件时，必须先确认文件所属的包/模块在项目中**已有对应的目录**，然后将文件放在该目录下，严禁创建新的一级源码目录")
            appendLine("4. 如果项目中已经存在同名的包路径，优先使用已有的路径，不要另建新路径")
            appendLine("5. 如果请求不明确，询问确认而不是猜测")
            appendLine("6. 对于复杂改动，先解释你的计划，再输出文件内容")
            appendLine("7. 优先使用项目中已存在的框架、模式和编码风格")
            appendLine("8. 输出文件修改后，简要总结你做了什么改动")
        }

        // Build messages: system prompt + full conversation history
        val messages = listOf(ChatMessage("system", systemPrompt)) + messageHistory.toList()

        // ── 准备流式渲染区域 ──
        removeMessagesFiller()
        val streamBubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        val streamTextArea = createStreamingArea(streamBubble)
        sendStopButton.text = "■ 停止"
        sendStopButton.toolTipText = "停止"

        // 先声明回调再启动流式请求（避免 lambda 引用未初始化的 val）
        val onTokenBlock: (String) -> Unit = { token ->
            ApplicationManager.getApplication().invokeLater {
                streamTextArea.append(token)
                scrollToBottom()
            }
        }

        val onCompleteBlock: (String, Usage?) -> Unit = { fullResponse, usage ->
            ApplicationManager.getApplication().invokeLater {
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) {
                    oldState.eventSource.cancel()
                    removeStreamingArea(oldState)
                }

                // Add the full response as an assistant message in the chat
                messageHistory.add(ChatMessage("assistant", fullResponse))
                renderAssistantMessage(fullResponse)

                // Parse and apply file operations
                val operations = parseFileOperations(fullResponse)
                if (operations.isNotEmpty()) {
                    applyFileOperations(operations)
                }

                usage?.let {
                    currentSession().totalTokens += it.totalTokens
                    addMessageLabel(
                        "── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})"
                    )
                }
                saveSessions()
                sendStopButton.text = "▶ 发送 (Enter)"
                sendStopButton.toolTipText = "发送消息"
                ensureMessagesFiller()
                scrollToBottom()
            }
        }

        val onErrorBlock: (Throwable) -> Unit = { error ->
            ApplicationManager.getApplication().invokeLater {
                val oldState = chatState.getAndSet(ChatState.Idle)
                if (oldState is ChatState.Streaming) {
                    oldState.eventSource.cancel()
                    removeStreamingArea(oldState)
                }

                addMessageLabel("[ERROR] ${error.message}")
                sendStopButton.text = "▶ 发送 (Enter)"
                sendStopButton.toolTipText = "发送消息"
                ensureMessagesFiller()
                scrollToBottom()
            }
        }

        val eventSource = client.chatStream(
            messages = messages,
            onToken = onTokenBlock,
            onComplete = onCompleteBlock,
            onError = onErrorBlock
        )

        // 设置为流式状态
        chatState.set(ChatState.Streaming(
            eventSource = eventSource,
            bubble = streamBubble
        ))
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
        val fileRegex = Regex(
            """<file\s+path="([^"]+)"(?:\s+action="([^"]*)")?\s*>(?:\n)?```(?:\w+)?\s*\n?([\s\S]*?)```\s*</file>""",
            RegexOption.MULTILINE
        )
        for (match in fileRegex.findAll(response)) {
            val path = match.groupValues[1].trim()
            val action = match.groupValues[2].ifBlank { "write" }
            val content = match.groupValues[3].trim()
            operations.add(FileOperation(path, content, action))
        }
        // Also match <file path="..."> without code fences (simple text)
        val simpleFileRegex = Regex(
            """<file\s+path="([^"]+)"(?:\s+action="([^"]*)")?\s*>([\s\S]*?)</file>""",
            RegexOption.MULTILINE
        )
        for (match in simpleFileRegex.findAll(response)) {
            val path = match.groupValues[1].trim()
            val action = match.groupValues[2].ifBlank { "write" }
            val content = match.groupValues[3].trim()
            // Only add if not already captured by the first regex
            if (operations.none { it.path == path }) {
                operations.add(FileOperation(path, content, action))
            }
        }
        return operations
    }

    /**
     * Apply file operations to the project using IntelliJ's virtual file system.
     * Performs path traversal validation and user confirmation.
     */
    private fun applyFileOperations(operations: List<FileOperation>) {
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
                addMessageLabel("⚠️ 跳过不安全的路径: ${op.path}")
                false
            } else {
                true
            }
        }

        if (safeOps.isEmpty()) {
            addMessageLabel("⚠️ 没有安全的文件操作可执行")
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
                        summaryLines.add("🗑️ 删除: ${op.path}")
                        deleteCount++
                    }
                }
                else -> {
                    val f = java.io.File(projectBasePath, op.path)
                    if (f.exists()) {
                        summaryLines.add("📝 修改: ${op.path}")
                    } else {
                        summaryLines.add("✨ 新建: ${op.path}")
                    }
                    writeCount++
                }
            }
        }
        val confirmMsg = "Agent 将执行以下文件操作：\n\n" + summaryLines.joinToString("\n") +
                "\n\n是否确认执行？"

        val confirmed = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            confirmMsg,
            "确认 Agent 操作",
            "确认执行",
            "取消",
            com.intellij.openapi.ui.Messages.getQuestionIcon()
        )
        if (confirmed != com.intellij.openapi.ui.Messages.YES) {
            addMessageLabel("⏸️ Agent 操作已取消")
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            var created = 0
            var modified = 0
            var deleted = 0

            for (op in safeOps) {
                val targetFile = java.io.File(projectBasePath, op.path)

                when (op.action) {
                    "delete" -> {
                        val vf = LocalFileSystem.getInstance().findFileByIoFile(targetFile)
                        if (vf != null && vf.exists()) {
                            vf.delete(this)
                            deleted++
                        }
                    }
                    else -> {
                        // ===== Smart path resolution =====
                        // Before creating new directories, check if the path maps to
                        // an existing source root in the project. The AI may output
                        // paths like "src/main/java/com/example/X.java" but the
                        // project uses "src/main/kotlin/..." — remap automatically.
                        val resolvedFile = resolveToExistingSourceRoot(targetFile)
                        val effectiveFile = resolvedFile ?: targetFile
                        val effectiveParent = effectiveFile.parentFile

                        // Only create parent dirs if they don't exist AND
                        // we couldn't find an existing matching source root.
                        if (resolvedFile == null && effectiveParent != null) {
                            // Check if ANY part of the path tree already exists
                            val existingAncestor = findExistingAncestor(effectiveParent)
                            if (existingAncestor == null) {
                                // No existing path segment found — warn the user
                                addMessageLabel("⚠️ 路径 ${op.path} 不匹配任何现有源码目录，将在新位置创建")
                            }
                        }
                        effectiveParent?.mkdirs()

                        val contentBytes = op.content.toByteArray(Charsets.UTF_8)
                        val isNew = !effectiveFile.exists()

                        if (isNew) {
                            // Create new file
                            val parentVFile = LocalFileSystem.getInstance()
                                .findFileByIoFile(effectiveFile.parentFile)
                            if (parentVFile != null && parentVFile.exists()) {
                                val vf = parentVFile.createChildData(this, effectiveFile.name)
                                vf.setBinaryContent(contentBytes)
                                created++
                            } else {
                                // Fallback: use java.io.File
                                effectiveFile.writeBytes(contentBytes)
                                LocalFileSystem.getInstance().refreshIoFiles(listOf(effectiveFile))
                                created++
                            }
                        } else {
                            // Update existing file
                            val vf = LocalFileSystem.getInstance().findFileByIoFile(effectiveFile)
                            if (vf != null && vf.exists()) {
                                vf.setBinaryContent(contentBytes)
                                modified++
                            } else {
                                effectiveFile.writeBytes(contentBytes)
                                LocalFileSystem.getInstance().refreshIoFiles(listOf(effectiveFile))
                                modified++
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

            val resultMsg = buildString {
                appendLine("✅ Agent 执行完成！")
                if (created > 0) appendLine("- 新建文件: $created 个")
                if (modified > 0) appendLine("- 修改文件: $modified 个")
                if (deleted > 0) appendLine("- 删除文件: $deleted 个")
                if (created == 0 && modified == 0 && deleted == 0) {
                    appendLine("- (没有文件操作被应用)")
                }
            }
            addMessageLabel(resultMsg)
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

    private fun stopStreaming() {
        val oldState = chatState.getAndSet(ChatState.Idle)
        if (oldState is ChatState.Streaming) {
            oldState.eventSource.cancel()
            removeStreamingArea(oldState)
        }
        sendStopButton.text = "▶ 发送 (Enter)"
        sendStopButton.toolTipText = "发送消息"
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

    override fun dispose() {
        currentInstance = null
        // 关闭时同步保存（跳过 debounce，确保数据落盘）
        saveTimer.stop()
        doSaveSessions()
    }

    // ==================================================================
    // Component builders — modular code block cards
    // ==================================================================

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
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

        val bubble = MessageBubble(
            project, MessageBubble.Role.USER, content,
            onDelete = onDeleteAction
        )
        // 添加 4px 左侧缩进 + 8px 右侧缩进，让气泡不完全贴边
        val padded = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 0, 8)
            add(bubble, BorderLayout.CENTER)
        }

        messagesPanel.add(padded, fillWidthConstraints)
        // 消息间距
        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)

        // Track for nav sidebar (only user messages)
        userMessages.add(MessageEntry(bubble, content))
        revalidateAndScroll()
    }

    /**
     * Render an assistant message — card style with accent bar + avatar.
     */
    private fun renderAssistantMessage(content: String) {
        showMessages()
        val bubble = MessageBubble(project, MessageBubble.Role.ASSISTANT, content)
        // 添加 6px 右侧缩进保持平衡
        val padded = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 0, 8)
            add(bubble, BorderLayout.CENTER)
        }

        messagesPanel.add(padded, fillWidthConstraints)
        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)

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
        val padded = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 0, 8)
            add(bubble, BorderLayout.CENTER)
        }

        messagesPanel.add(padded, fillWidthConstraints)
        messagesPanel.add(Box.createVerticalStrut(4), fillWidthConstraints)
        revalidateAndScroll()

        // ── Start thinking animation ──
        val textArea = bubble.streamTextArea!!
        textArea.text = "... 思考中 ◐"
        return textArea
    }

    /** Remove the streaming bubble and stop the thinking animation. */
    private fun removeStreamingArea(state: ChatState.Streaming) {
        val bubble = state.bubble
        for (i in messagesPanel.componentCount - 1 downTo 0) {
            val c = messagesPanel.getComponent(i)
            // c is now the row; the bubble is nested inside it
            if (c is JPanel && (c === bubble || findComponent(c, bubble))) {
                messagesPanel.remove(c)
                messagesPanel.revalidate()
                messagesPanel.repaint()
                return
            }
        }
    }

    /** Recursively check if a component tree contains the given target. */
    private fun findComponent(parent: java.awt.Container, target: java.awt.Component): Boolean {
        for (c in parent.components) {
            if (c === target) return true
            if (c is java.awt.Container && findComponent(c, target)) return true
        }
        return false
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
