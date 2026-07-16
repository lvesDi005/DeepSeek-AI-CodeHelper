package com.deepseek.plugin.agent

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.LlmProviderRegistry
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Generic base for agent actions: selects code, calls the configured AI provider,
 * shows streaming result in a green-themed balloon popup below the selected code.
 */
abstract class BaseAgentAction : AnAction() {

    protected val client = DeepSeekApiClient()

    abstract val systemPrompt: String
    abstract val progressTitle: String
    abstract val emptySelectionMessage: String
    /** I18n key for the right-click menu text (e.g. "agent.menu.explain") */
    abstract val menuTextKey: String

    /** Captured from [actionPerformed] so subclasses can use it in [showResult]. */
    protected var capturedEditor: Editor? = null
    protected var capturedProject: Project? = null

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        e.presentation.text = I18n.tr(menuTextKey)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        capturedProject = project
        val settings = DeepSeekSettings.instance
        val currentProvider = LlmProviderRegistry.get(settings.provider)
        if (currentProvider.apiKey(settings).isBlank()) {
            com.intellij.openapi.ui.Messages.showWarningDialog(
                project,
                I18n.tr("agent.config.required"),
                I18n.tr("agent.config.title")
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
                editor.document.text
            }
        } else {
            ""
        }

        if (selectedText.isBlank() && editor == null) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, emptySelectionMessage, I18n.tr("agent.title"))
            return
        }

        // 在选中代码下方显示加载提示
        if (editor != null) {
            AgentResultPopup.showLoadingHint(editor)
        }

        // 流式调用 — 不再拼接 DOMAIN_RESTRICTION_PROMPT
        val userMessage = if (selectedText.isBlank()) {
            emptySelectionMessage
        } else {
            "```\n$selectedText\n```"
        }

        // 注入语言指令（使用 AI 输出语言设置，与 Chat 面板统一）
        val langInstruction = if (settings.aiLanguage == "en")
            "\n\nPlease reply in English."
        else
            "\n\n请用中文回复。"

        val messages = listOf(
            ChatMessage("system", systemPrompt + langInstruction),
            ChatMessage("user", userMessage)
        )

        client.chatStream(
            messages = messages,
            onToken = { token ->
                ApplicationManager.getApplication().invokeLater {
                    AgentResultPopup.appendStreamingContent(token)
                }
            },
            onComplete = { _, _ ->
                ApplicationManager.getApplication().invokeLater {
                    AgentResultPopup.finishStreaming()
                }
            },
            onError = { error ->
                ApplicationManager.getApplication().invokeLater {
                    AgentResultPopup.dismiss()
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        I18n.tr("agent.api.error", error.message),
                        I18n.tr("agent.title")
                    )
                }
            }
        )
    }

    /**
     * 在编辑器选中代码下方以绿色背景弹窗显示 AI 响应结果。
     * 子类可覆写此方法以自定义展示行为。
     */
    protected open fun showResult(project: Project, originalCode: String, response: String) {
        val editor = capturedEditor
        if (editor != null) {
            AgentResultPopup.showResult(editor, project, originalCode, response)
        }
    }
}
