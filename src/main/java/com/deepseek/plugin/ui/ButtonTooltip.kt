package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Create a small, flat toolbar-style [JButton] with the given [icon] and
 * a Swing tooltip that shows on mouse hover.
 *
 * The returned button follows the same pattern as
 * [com.deepseek.plugin.chat.ChatPanel.createSmallRoundButton] — the tooltip
 * is set via [JComponent.setToolTipText], which auto-registers the component
 * with [javax.swing.ToolTipManager].
 *
 * @param icon      The icon to display on the button.
 * @param tooltip   The tooltip text shown on hover.
 * @param size      The width and height of the button in pixels (default 24).
 * @param tooltipKey Optional i18n key for the tooltip. When provided, the tooltip
 *                   is tracked via [I18n.tooltip] and refreshes automatically on
 *                   language switch.
 * @param onClick   Called when the button is clicked.
 */
fun createToolbarButton(
    icon: javax.swing.Icon,
    tooltip: String,
    size: Int = 24,
    tooltipKey: String? = null,
    onClick: () -> Unit
): JButton {
    val dim = Dimension(JBUI.scale(size), JBUI.scale(size))
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
        if (tooltipKey != null) {
            I18n.tooltip(this, tooltipKey)
        } else {
            toolTipText = tooltip
        }
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        preferredSize = dim
        minimumSize = dim
        maximumSize = dim
        addActionListener { onClick() }
    }
}
