package com.deepseek.plugin.chat

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.Usage
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
import com.deepseek.plugin.ui.QuestionNavSidebar
import com.deepseek.plugin.ui.ResponseSegment
import com.deepseek.plugin.ui.SelectedCodePreview
import com.deepseek.plugin.ui.SessionBar
import com.deepseek.plugin.ui.UsageDialog
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import okhttp3.sse.EventSource
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.DefaultCaret

data class ChatSession(
    val name: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var totalTokens: Int = 0,
    var lastActiveTime: Long = System.currentTimeMillis()
)

class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val client = DeepSeekApiClient()
    private val contextProvider = ProjectContextProvider(project)
    private val sessionStore = SessionStore(project.basePath)
    private val sessions = mutableListOf<ChatSession>()
    private var currentSessionIndex = 0
    private var currentEventSource: EventSource? = null
    private var isStreaming = false
    private var sessionCounter = 1

    // ── Selected code preview state ──
    private data class SelectedContext(
        val fileName: String,
        val startLine: Int,
        val endLine: Int,
        val snippet: String
    )

    private var selectedContext: SelectedContext? = null
    private val selectedCodePreviewPanel = JPanel(BorderLayout()).apply {
        isVisible = false
    }

    // ── Question navigation sidebar ──
    private data class MessageEntry(
        val panel: JPanel,
        val text: String,
        var responsePanel: JPanel? = null   // linked AI response, filled later
    )
    private val userMessages = mutableListOf<MessageEntry>()
    private val questionNavSidebar = QuestionNavSidebar()

    // ── File attachment state ──
    private val attachedFiles = mutableListOf<AttachedFile>()
    private val fileAttachmentPanel = JPanel(BorderLayout()).apply {
        isVisible = false
    }

    // ── Streaming buffer ──
    private val streamBuffer = StringBuilder()
    private var streamScrollPane: JBScrollPane? = null
    private var streamTextArea: JBTextArea? = null
    private var streamingBubble: MessageBubble? = null

    // ── UI Components ──

    private val sessionComboBox = JComboBox<String>()

    /** Vertical panel that holds all rendered messages. */
    private val messagesPanel = JPanel().apply {
        // GridBagLayout reliably fills width with the right constraints
        layout = GridBagLayout()
        background = JBColor(0xFFFFFF, 0x1E1E1E)
    }

    /** GridBagConstraints: each child fills its row horizontally. */
    private val fillWidthConstraints = GridBagConstraints().apply {
        fill = GridBagConstraints.HORIZONTAL
        weightx = 1.0
        gridwidth = GridBagConstraints.REMAINDER
        anchor = GridBagConstraints.WEST
    }

    /** Constraints for vertical spacers — no horizontal fill. */
    private val spacerConstraints = GridBagConstraints().apply {
        fill = GridBagConstraints.NONE
        weightx = 0.0
        gridwidth = GridBagConstraints.REMAINDER
        anchor = GridBagConstraints.WEST
    }

    private val messagesScrollPane = JBScrollPane(messagesPanel).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
        border = JBUI.Borders.empty()
    }

    private val inputArea = JBTextArea(4, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JBUI.Fonts.create("Monospaced", 13)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && e.isControlDown && !isStreaming) {
                    e.consume()
                    sendMessage()
                }
            }
        })
    }

    private val sendButton = createInputButton("Send (Ctrl+Enter)", { sendMessage() }, isPrimary = true)
    private val stopButton = createInputButton("Stop", { stopStreaming() }, isPrimary = false).apply {
        isEnabled = false
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
            add(JSeparator())
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
                    questionNavSidebar.clear()
                    questionNavSidebar.isVisible = false
                    saveSessions()
                }
            ))
            add(JSeparator())
        }

        val bottomPanel = ChatInputBar(
            inputScrollPane = JBScrollPane(inputArea),
            selectedCodePanel = selectedCodePreviewPanel,
            fileAttachmentPanel = fileAttachmentPanel,
            uploadButton = createUploadButton(),
            stopButton = stopButton,
            sendButton = sendButton
        )

        // Wrap messages scroll pane with the navigation sidebar
        val messagesWithNav = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(questionNavSidebar, BorderLayout.WEST)
            add(messagesScrollPane, BorderLayout.CENTER)
        }

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            topComponent = messagesWithNav
            bottomComponent = bottomPanel
            resizeWeight = 0.75
            dividerSize = 3
        }

        add(topPanel, BorderLayout.NORTH)
        add(splitPane, BorderLayout.CENTER)

        // ── Width listener for question nav sidebar ──
        // Show sidebar when there's enough horizontal space
        messagesWithNav.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                updateNavSidebarVisibility()
            }
        })
        questionNavSidebar.isVisible = false

        // If we have saved sessions, render the most recently active one
        if (saved != null && sessions.isNotEmpty()) {
            // Find the most recent session (last used)
            val lastActiveIdx = sessions.indices.maxByOrNull { sessions[it].lastActiveTime } ?: 0
            switchToSession(lastActiveIdx)
        } else {
            addMessageLabel("=== DeepSeek AI Chat ===\nCtrl+Enter 发送消息。选中代码自动填入输入框。\n")
        }
        setupSelectionListener()

        // ── Scroll listener: keep sidebar dots synced with viewport ──
        messagesScrollPane.verticalScrollBar.addAdjustmentListener { updateSidebarVisibleRange() }
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
                    snippet = context.snippet,
                    onDismiss = { setSelectedContext(null) }
                ),
                BorderLayout.CENTER
            )
            selectedCodePreviewPanel.isVisible = true
        } else {
            selectedCodePreviewPanel.isVisible = false
        }

        selectedCodePreviewPanel.revalidate()
        selectedCodePreviewPanel.repaint()
    }

    // ===== File attachment management =====

    private fun createUploadButton(): JButton {
        // Hidden file chooser — triggered on button click
        return makeTextBtn("\uD83D\uDCCE", "上传文件") { openFileChooser() }
    }

    private fun openFileChooser() {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "选择要上传的文件"
            fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = true
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                "支持的格式 (*.java, *.kt, *.xml, *.json, *.yml, *.properties, *.txt, *.md, *.sql, *.gradle, *.ts, *.js, *.css, *.html)",
                "java", "kt", "kts", "xml", "json", "yaml", "yml", "properties",
                "txt", "md", "sql", "gradle", "ts", "js", "css", "html", "py", "go", "rs", "rb"
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
     * Build the file context string from all attached files and clear them.
     */
    private fun buildFileContext(): String {
        if (attachedFiles.isEmpty()) return ""

        val sb = StringBuilder()
        for (file in attachedFiles.toList()) {
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
        attachedFiles.clear()
        refreshFileAttachmentPanel()
        return sb.toString()
    }

    // ===== Toolbar =====
    private fun makeTextBtn(text: String, tooltip: String, action: () -> Unit): JButton {
        return object : JButton(text) {
            override fun getToolTipLocation(e: java.awt.event.MouseEvent?): java.awt.Point? {
                return java.awt.Point(0, height + 2)
            }
        }.apply {
            this.toolTipText = tooltip
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            font = font.deriveFont(13f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(3, 6, 3, 6)
            addActionListener { action() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isOpaque = true
                    background = JBColor(0xE8E8E8, 0x4A4A4A)
                }
                override fun mouseExited(e: MouseEvent) {
                    isOpaque = false
                }
            })
        }
    }

    /**
     * Create a styled button for the input bar (Send/Stop).
     * @param isPrimary true for the primary action (Send), uses accent color
     */
    private fun createInputButton(text: String, onClick: () -> Unit, isPrimary: Boolean): JButton {
        return object : JButton(text) {
            override fun getToolTipLocation(e: MouseEvent?): java.awt.Point? {
                return java.awt.Point(0, height + 2)
            }
        }.apply {
            toolTipText = text
            isFocusPainted = false
            font = font.deriveFont(Font.BOLD, 12f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(5, 12, 5, 12)
            if (isPrimary) {
                isContentAreaFilled = true
                background = JBColor(0x1A73E8, 0x285CE6)
                foreground = JBColor.WHITE
            } else {
                isContentAreaFilled = false
                isBorderPainted = false
            }
            addActionListener { onClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    if (isPrimary) {
                        background = JBColor(0x1557B0, 0x3B72E8)
                    } else {
                        isOpaque = true
                        background = JBColor(0xE8E8E8, 0x4A4A4A)
                    }
                }
                override fun mouseExited(e: MouseEvent) {
                    if (isPrimary) {
                        background = JBColor(0x1A73E8, 0x285CE6)
                    } else {
                        if (!isEnabled) {
                            isOpaque = false
                        } else {
                            isOpaque = false
                        }
                    }
                }
            })
        }
    }

    // ===== Session bar =====
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
        questionNavSidebar.clear()
        questionNavSidebar.isVisible = false
        addMessageLabel("=== $name ===")
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
        questionNavSidebar.clear()
        questionNavSidebar.isVisible = false

        // Create a new session and switch to it
        createNewSession()
    }

    fun switchToSession(index: Int) {
        stopStreaming()
        currentSessionIndex = index
        sessionComboBox.selectedIndex = index
        messagesPanel.removeAll()
        userMessages.clear()
        questionNavSidebar.clear()
        questionNavSidebar.isVisible = false
        val session = sessions[index]
        addMessageLabel("=== ${session.name} ===")
        for (msg in session.messages) {
            when (msg.role) {
                "user" -> renderUserMessage(msg.content)
                "assistant" -> renderAssistantMessage(msg.content)
            }
        }
        saveSessions()
        scrollToBottom()
        // Schedule sidebar update after layout + scroll are settled
        SwingUtilities.invokeLater { rebuildNavSidebar() }
    }

    // ==================================================================
    // Question navigation sidebar
    // ==================================================================

    /** Rebuild node list — call when messages are added/removed/switched. */
    private fun rebuildNavSidebar() {
        val nodeList = userMessages.map { entry ->
            // Scroll to the AI response panel if available, otherwise fall back to user panel
            val targetPanel = entry.responsePanel ?: entry.panel
            QuestionNavSidebar.NodeData(targetPanel, entry.text)
        }
        questionNavSidebar.setNodes(nodeList)
        updateNavSidebarVisibility()
        SwingUtilities.invokeLater { updateSidebarVisibleRange() }
    }

    /** Show/hide sidebar based on available width of the messages container. */
    private fun updateNavSidebarVisibility() {
        if (userMessages.isEmpty()) {
            questionNavSidebar.isVisible = false
            return
        }
        val containerWidth = questionNavSidebar.parent?.width ?: return
        questionNavSidebar.isVisible = questionNavSidebar.shouldShow(containerWidth)
    }

    /**
     * Called on scroll — finds the user message nearest the top of the viewport
     * and shifts the sidebar's visible dot window (6 nodes) to center around it.
     */
    private fun updateSidebarVisibleRange() {
        val total = userMessages.size
        if (total == 0) return

        val visibleRect = messagesScrollPane.viewport.viewRect

        // Find the first user message whose wrapper crosses the top of the viewport
        var firstVisibleIdx = 0
        for (i in userMessages.indices) {
            val entry = userMessages[i]
            val targetPanel = entry.responsePanel ?: entry.panel
            val wrapper = targetPanel.parent ?: continue
            // wrapper.y is relative to messagesPanel (the viewport's view)
            if (wrapper.y + wrapper.height / 2 > visibleRect.y) {
                firstVisibleIdx = i
                break
            }
        }

        // Center the 6-node window around firstVisibleIdx
        if (total <= QuestionNavSidebar.MAX_VISIBLE_NODES) {
            questionNavSidebar.setVisibleRange(0)
        } else {
            val half = QuestionNavSidebar.MAX_VISIBLE_NODES / 2
            val start = (firstVisibleIdx - half).coerceIn(0, total - QuestionNavSidebar.MAX_VISIBLE_NODES)
            questionNavSidebar.setVisibleRange(start)
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

        val settings = DeepSeekSettings.instance
        if (settings.apiKey.isBlank()) {
            addMessageLabel("[ERROR] 请在 Settings → Tools → DeepSeek AI 配置 API Key.")
            return
        }

        inputArea.text = ""

        // Build user message: prepend file context + selected code context
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
            append(text)
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
        isStreaming = true
        sendButton.isEnabled = false
        stopButton.isEnabled = true

        // Prepare a temporary streaming area
        streamBuffer.setLength(0)
        val streamingArea = createStreamingArea()
        streamTextArea = streamingArea.first
        streamScrollPane = streamingArea.second

        currentEventSource = client.chatStream(
            messages = messageHistory.toList(),
            onToken = { token ->
                ApplicationManager.getApplication().invokeLater {
                    streamBuffer.append(token)
                    streamTextArea?.append(token)
                    scrollToBottom()
                }
            },
            onComplete = { fullResponse, usage ->
                ApplicationManager.getApplication().invokeLater {
                    // Remove the temporary streaming component
                    removeStreamingArea()
                    streamBuffer.setLength(0)

                    messageHistory.add(ChatMessage("assistant", fullResponse))
                    renderAssistantMessage(fullResponse)

                    usage?.let {
                        currentSession().totalTokens += it.totalTokens
                        addMessageLabel(
                            "── Token: ${it.totalTokens} (P:${it.promptTokens} C:${it.completionTokens})"
                        )
                    }
                    saveSessions()
                    isStreaming = false
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    currentEventSource = null
                    scrollToBottom()
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    removeStreamingArea()
                    streamBuffer.setLength(0)
                    addMessageLabel("[ERROR] ${error.message}")
                    isStreaming = false
                    sendButton.isEnabled = true
                    stopButton.isEnabled = false
                    currentEventSource = null
                    scrollToBottom()
                }
            }
        )
    }

    private fun stopStreaming() {
        currentEventSource?.cancel()
        currentEventSource = null
        isStreaming = false
        sendButton.isEnabled = true
        stopButton.isEnabled = false
        removeStreamingArea()
        streamBuffer.setLength(0)
    }

    private fun saveSessions() {
        currentSession().lastActiveTime = System.currentTimeMillis()
        sessionStore.save(sessions, sessionCounter)
    }

    override fun dispose() {
        // Save sessions when the tool window is closed
        saveSessions()
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
     * Wrap a component in a panel that constrains its horizontal width to a
     * fraction of the available space (default 0.75 = 75%). The message panel
     * sits on the left and the remaining space is empty.
     */
    private fun wrapWithWidthConstraint(
        component: JComponent,
        weight: Double = 1.0
    ): JPanel {
        val wrapper = JPanel(GridBagLayout()).apply { isOpaque = false }
        val msgConstraints = GridBagConstraints().apply {
            fill = GridBagConstraints.BOTH
            weightx = weight
            gridwidth = GridBagConstraints.REMAINDER
            anchor = GridBagConstraints.WEST
        }
        wrapper.add(component, msgConstraints)
        return wrapper
    }

    /**
     * Add a simple text label (system messages, tokens, errors).
     */
    private fun addMessageLabel(text: String) {
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
        messagesPanel.add(Box.createVerticalStrut(2), spacerConstraints)
        revalidateAndScroll()
    }

    /**
     * Render a user message as a MessageBubble with "You:" header.
     */
    private fun renderUserMessage(content: String) {
        val bubble = MessageBubble(project, MessageBubble.Role.USER, content)

        messagesPanel.add(wrapWithWidthConstraint(bubble), fillWidthConstraints)
        messagesPanel.add(Box.createVerticalStrut(4), spacerConstraints)

        // Track for nav sidebar (only user messages)
        userMessages.add(MessageEntry(bubble, content))
        revalidateAndScroll()
        // Schedule sidebar update after layout is final
        SwingUtilities.invokeLater { rebuildNavSidebar() }
    }

    /**
     * Render an assistant message — parse code blocks and render each as a
     * modular MessageBubble with CodeBlockCards inside.
     */
    private fun renderAssistantMessage(content: String) {
        val bubble = MessageBubble(project, MessageBubble.Role.ASSISTANT, content)

        messagesPanel.add(wrapWithWidthConstraint(bubble), fillWidthConstraints)
        messagesPanel.add(Box.createVerticalStrut(4), spacerConstraints)

        // Link this response to the most recent user question without a response yet
        val lastUserMsg = userMessages.lastOrNull { it.responsePanel == null }
        lastUserMsg?.responsePanel = bubble

        revalidateAndScroll()
        // Schedule sidebar update after layout settles (assistant response changes content height)
        SwingUtilities.invokeLater { rebuildNavSidebar() }
    }

    /**
     * Create a temporary streaming message bubble.
     * Returns (JBTextArea, JBScrollPane) pair.
     */
    private fun createStreamingArea(): Pair<JBTextArea, JBScrollPane> {
        val bubble = MessageBubble(project, MessageBubble.Role.STREAMING)
        streamingBubble = bubble

        messagesPanel.add(wrapWithWidthConstraint(bubble), fillWidthConstraints)
        revalidateAndScroll()
        return Pair(bubble.streamTextArea!!, bubble.streamScrollPane!!)
    }

    /** Remove the streaming bubble. */
    private fun removeStreamingArea() {
        val bubble = streamingBubble ?: return
        for (i in messagesPanel.componentCount - 1 downTo 0) {
            val c = messagesPanel.getComponent(i)
            if (c === bubble || (c is JPanel && c.componentCount > 0 && findComponent(c, bubble))) {
                messagesPanel.remove(c)
                messagesPanel.revalidate()
                messagesPanel.repaint()
                streamingBubble = null
                return
            }
        }
        // Fallback: remove any wrapped stream bubble
        streamingBubble = null
    }

    /** Recursively check if a component tree contains the given target. */
    private fun findComponent(parent: java.awt.Container, target: java.awt.Component): Boolean {
        for (c in parent.components) {
            if (c === target) return true
            if (c is java.awt.Container && findComponent(c, target)) return true
        }
        return false
    }

    private fun revalidateAndScroll() {
        messagesPanel.revalidate()
        messagesPanel.repaint()
        scrollToBottom()
    }
}
