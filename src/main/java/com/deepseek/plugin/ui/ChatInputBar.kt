package com.deepseek.plugin.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
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
 * │  ⬆ upload   [ ⏸ ] [ ▶ ]    │
 * └──────────────────────────────┘
 *
 * @param inputScrollPane   The scroll pane wrapping the text input area.
 * @param selectedCodePanel Panel shown above input when code is selected (nullable).
 * @param fileAttachmentPanel Panel shown above input for attached files (nullable).
 * @param uploadButton  Button to open file chooser.
 * @param sendStopButton Combined send/stop button — shows ▶ when idle, ⏸ during streaming.
 */
class ChatInputBar(
    inputScrollPane: JBScrollPane,
    selectedCodePanel: JPanel?,
    fileAttachmentPanel: JPanel?,
    modeSelector: JComponent?,
    uploadButton: JComponent,
    sendStopButton: JComponent
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
                JBUI.Borders.empty(6, 8, 6, 8)
            )
            if (modeSelector != null) {
                add(modeSelector)
                add(Box.createHorizontalStrut(10))
            }
            add(uploadButton)
            add(Box.createHorizontalGlue())
            add(sendStopButton)
        }
        add(buttonBar, BorderLayout.SOUTH)
    }
}
