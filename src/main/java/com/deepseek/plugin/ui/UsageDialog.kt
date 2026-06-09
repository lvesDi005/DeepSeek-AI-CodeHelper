package com.deepseek.plugin.ui

import com.deepseek.plugin.chat.ChatSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Dialog showing token usage across all sessions.
 *
 * @param project  IntelliJ project (for dialog parent)
 * @param sessions All chat sessions
 * @param currentSessionIndex Index of the currently active session
 */
class UsageDialog(
    project: Project,
    private val sessions: List<ChatSession>,
    private val currentSessionIndex: Int
) : DialogWrapper(project, true) {

    init {
        title = "用量查看"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val totalTokens = sessions.sumOf { it.totalTokens }
        val sb = StringBuilder()
        sb.appendLine("Token 用量统计")
        sb.appendLine("─────────────────────")
        for ((i, s) in sessions.withIndex()) {
            val mark = if (i == currentSessionIndex) " * " else "   "
            sb.appendLine("$mark${s.name}: ${s.totalTokens} tokens")
        }
        sb.appendLine("─────────────────────")
        sb.appendLine("总计: $totalTokens tokens")

        val textArea = JBTextArea(sb.toString()).apply {
            isEditable = false
            font = JBUI.Fonts.create("Monospaced", 13)
            margin = JBUI.insets(10, 10, 10, 10)
        }

        return JBScrollPane(textArea).apply {
            preferredSize = Dimension(380, 250)
            border = JBUI.Borders.empty()
        }
    }
}
