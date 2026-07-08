package com.deepseek.plugin.agent

import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT
import com.deepseek.plugin.settings.DeepSeekSettings
import com.deepseek.plugin.ui.CodeBlockCard
import com.deepseek.plugin.ui.MessageTable
import com.deepseek.plugin.ui.ResponseSegment
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Generic base for agent actions: selects code, calls DeepSeek with a system prompt,
 * shows result in a dialog or inserts into editor.
 */
abstract class BaseAgentAction : AnAction() {

    protected val client = DeepSeekApiClient()


    abstract val systemPrompt: String
    abstract val progressTitle: String
    abstract val emptySelectionMessage: String

    /** Captured from [actionPerformed] so subclasses can use it in [showResult]. */
    protected var capturedEditor: Editor? = null
    protected var capturedProject: Project? = null

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        capturedProject = project
        val settings = DeepSeekSettings.instance
        if (settings.apiKey.isBlank()) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                "Please configure your DeepSeek API Key in Settings → Tools → DeepSeek AI.",
                "API Key Required"
            )
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        capturedEditor = editor
        val selectedText = if (editor != null) {
            val selectionModel = editor.selectionModel
            if (selectionModel.hasSelection()) {
                selectionModel.selectedText ?: ""
            } else {
                editor.document.text  // No selection → use entire file
            }
        } else {
            ""
        }
        

        if (selectedText.isBlank() && editor == null) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, emptySelectionMessage, "DeepSeek AI")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, progressTitle, true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Calling DeepSeek..."

                val userMessage = if (selectedText.isBlank()) {
                    emptySelectionMessage
                } else {
                    "```\n$selectedText\n```"
                }

                val result = client.chatSync("$DOMAIN_RESTRICTION_PROMPT\n\n$systemPrompt", userMessage)
                result.fold(
                    onSuccess = { response ->
                        ApplicationManager.getApplication().invokeLater {
                            showResult(project, selectedText, response)
                        }
                    },
                    onFailure = { error ->
                        ApplicationManager.getApplication().invokeLater {
                            com.intellij.openapi.ui.Messages.showErrorDialog(
                                project,
                                "API Error: ${error.message}",
                                "DeepSeek AI"
                            )
                        }
                    }
                )
            }
        })
    }

    /**
     * Default result display: modular rendering with CodeBlockCard.
     * Parses the response for code blocks and renders each as a card
     * with copy + insert buttons. Subclasses may override.
     */
    protected open fun showResult(project: Project, originalCode: String, response: String) {
        val segments = CodeBlockCard.parseResponse(response)

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 8, 8, 8)
        }

        var hasCodeBlock = false

        for (segment in segments) {
            when (segment) {
                is ResponseSegment.Text -> {
                    val textArea = JBTextArea(segment.content).apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                        font = JBUI.Fonts.create("Monospaced", 13)
                        margin = JBUI.insets(2, 0, 2, 0)
                        border = JBUI.Borders.empty()
                        background = JBColor(0xFFFFFF, 0x1E1E1E)
                    }
                    contentPanel.add(textArea)
                }
                is ResponseSegment.Code -> {
                    hasCodeBlock = true
                    contentPanel.add(Box.createVerticalStrut(4))
                    contentPanel.add(
                        CodeBlockCard(
                            project = project,
                            code = segment.content,
                            language = segment.language,
                            showInsertButton = true
                        )
                    )
                    contentPanel.add(Box.createVerticalStrut(4))
                }
                is ResponseSegment.Table -> {
                    contentPanel.add(Box.createVerticalStrut(4))
                    contentPanel.add(MessageTable(segment.headers, segment.rows))
                    contentPanel.add(Box.createVerticalStrut(4))
                }
            }
        }

        // If no code blocks found, fall back to simple text rendering
        val scrollPane = if (!hasCodeBlock) {
            JBScrollPane(createPlainTextArea(response)).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(500, 350)
            }
        } else {
            JBScrollPane(contentPanel).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(500, 400)
            }
        }

        val dialog = object : DialogWrapper(project, true) {
            init {
                title = "DeepSeek AI"
                init()
            }
            override fun createCenterPanel(): JComponent = scrollPane
            override fun getPreferredFocusedComponent(): JComponent? = null
        }
        dialog.show()
    }

    private fun createPlainTextArea(text: String): JComponent {
        val textArea = JBTextArea(text.trim()).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = JBUI.Fonts.create("Monospaced", 12)
            margin = JBUI.insets(6)
        }
        return JBScrollPane(textArea).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(500, 350)
        }
    }

}

