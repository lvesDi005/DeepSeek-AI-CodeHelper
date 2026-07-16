package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * The session management bar below the toolbar.
 *
 * Layout:
 * ┌──────────────────────────────────────┐
 * │ [▼ 会话 1]  [+]  [🗑]               │
 * └──────────────────────────────────────┘
 *
 * @param sessionComboBox   The combo box with session names.
 * @param onNewSession      Called when "新建会话" is clicked.
 * @param onClearCurrent    Called when "清除当前会话" is clicked.
 */
class SessionBar(
    private val sessionComboBox: JComboBox<String>,
    onNewSession: () -> Unit,
    onClearCurrent: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private const val BAR_HEIGHT = 24  // match toolbar button size

        /** Compute the combo box width based on the longest item text. */
        private fun computePreferredComboWidth(combo: JComboBox<String>): Int {
            val font = combo.font.deriveFont(11f)
            val metrics = combo.getFontMetrics(font)
            var maxWidth = 0
            for (i in 0 until combo.itemCount) {
                val w = metrics.stringWidth(combo.getItemAt(i) ?: "")
                if (w > maxWidth) maxWidth = w
            }
            // fallback for empty combo
            if (maxWidth == 0) maxWidth = metrics.stringWidth("会话 99")
            // add padding for arrow button + insets + rendering margin (56px unscaled)
            return JBUI.scale(maxWidth + 56)
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(1, 4, 1, 4)  // match ChatToolbar border

        // Shrink combo box to match button height; width auto-fits text
        val comboWidth = computePreferredComboWidth(sessionComboBox)
        val comboDim = Dimension(comboWidth, JBUI.scale(BAR_HEIGHT))
        sessionComboBox.apply {
            font = font.deriveFont(11f)     // smaller font to fit height
            preferredSize = comboDim
            minimumSize = Dimension(JBUI.scale(80), JBUI.scale(BAR_HEIGHT))
            maximumSize = Dimension(JBUI.scale(Short.MAX_VALUE.toInt()), JBUI.scale(BAR_HEIGHT))
        }

        // Left: session combo + new session + clear current
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(sessionComboBox)
            add(Box.createHorizontalStrut(4))
            add(createToolbarButton(
                icon = AllIcons.General.Add,
                tooltip = I18n.tr("session.new"),
                onClick = onNewSession
            ))
            add(Box.createHorizontalStrut(2))
            add(createToolbarButton(
                icon = AllIcons.Actions.GC,
                tooltip = I18n.tr("session.clear.current"),
                onClick = onClearCurrent
            ))
        }
        add(leftPanel, BorderLayout.WEST)
    }
}
