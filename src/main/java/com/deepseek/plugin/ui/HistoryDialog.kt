package com.deepseek.plugin.ui

import com.deepseek.plugin.chat.ChatSession
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Dialog showing session history for quick switching.
 *
 * @param project       IntelliJ project (for dialog parent)
 * @param sessions      All chat sessions
 * @param currentSessionIndex Index of the currently active session
 * @param onSwitch      Called when the user selects a session to switch to
 */
class HistoryDialog(
    project: Project,
    private val sessions: List<ChatSession>,
    private val currentSessionIndex: Int,
    private val onSwitch: (Int) -> Unit
) : DialogWrapper(project, true) {

    private data class SessionItem(val index: Int, val session: ChatSession) {
        override fun toString(): String {
            val msgCount = session.messages.size
            val tokens = if (session.totalTokens > 0) " · ${session.totalTokens} tokens" else ""
            return "${session.name}  ($msgCount 条消息$tokens)"
        }
    }

    init {
        title = "会话历史"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val listModel = DefaultListModel<SessionItem>()
        for ((i, s) in sessions.withIndex()) {
            listModel.addElement(SessionItem(i, s))
        }

        val sessionList = JBList(listModel).apply {
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is SessionItem && value.index == currentSessionIndex) {
                        icon = AllIcons.Actions.Checked
                        if (!isSelected) {
                            background = JBColor(0xE8F0FE, 0x2D3A4A)
                        }
                    } else {
                        icon = null
                    }
                    return c
                }
            }
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        sessionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val item = sessionList.selectedValue as? SessionItem ?: return
                    closeDialog(sessionList)
                    onSwitch(item.index)
                }
            }
        })

        val openBtn = JButton("进入会话")
        openBtn.addActionListener {
            val item = sessionList.selectedValue as? SessionItem ?: return@addActionListener
            closeDialog(openBtn)
            onSwitch(item.index)
        }

        val panel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10, 10, 10, 10)
            add(JBScrollPane(sessionList).apply {
                preferredSize = Dimension(350, 220)
                border = JBUI.Borders.customLine(JBColor.border())
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(openBtn)
            }, BorderLayout.SOUTH)
        }
        return panel
    }

    private fun closeDialog(component: Component) {
        SwingUtilities.getWindowAncestor(component)?.dispose()
    }
}
