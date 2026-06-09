package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Cursor
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * The input bar at the bottom of the chat panel.
 *
 * Layout (top→bottom):
 * ┌──────────────────────────────┐
 * │  [selected code preview]     │  ← optional, shown when code is selected
 * │  [file attachment preview]   │  ← optional, shown when files are attached
 * ├──────────────────────────────┤
 * │  [text input area]           │
 * ├──────────────────────────────┤
 * │  📎 upload  [ stop ] [Send] │
 * └──────────────────────────────┘
 *
 * @param inputScrollPane   The scroll pane wrapping the text input area.
 * @param selectedCodePanel Panel shown above input when code is selected (nullable).
 * @param fileAttachmentPanel Panel shown above input for attached files (nullable).
 * @param uploadButton  Button to open file chooser.
 * @param stopButton    Button to stop streaming.
 * @param sendButton    Primary send button.
 */
class ChatInputBar(
    inputScrollPane: JBScrollPane,
    selectedCodePanel: JPanel?,
    fileAttachmentPanel: JPanel?,
    uploadButton: JButton,
    stopButton: JButton,
    sendButton: JButton
) : JPanel(BorderLayout()) {

    init {
        // ── Preview stack (North) ──
        val previewStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            if (selectedCodePanel != null) add(selectedCodePanel)
            if (fileAttachmentPanel != null) add(fileAttachmentPanel)
        }
        add(previewStack, BorderLayout.NORTH)

        // ── Input area (Center) ──
        add(inputScrollPane, BorderLayout.CENTER)

        // ── Button bar (South) ──
        val buttonBar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(4, 4, 4, 4)
            )
            add(uploadButton)
            add(Box.createHorizontalGlue())
            add(stopButton)
            add(Box.createHorizontalStrut(6))
            add(sendButton)
            add(Box.createHorizontalStrut(4))
        }
        add(buttonBar, BorderLayout.SOUTH)
    }
}
