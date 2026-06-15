package com.deepseek.plugin.ui

import com.intellij.openapi.actionSystem.impl.ActionButton
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Wrap an [ActionButton] in a transparent panel that provides a standard
 * Swing tooltip.  ActionButton's own tooltip system is designed for
 * ActionToolbar and may not show tooltips when used standalone.
 *
 * The returned [JPanel] can have its [toolTipText] updated at any time
 * to change the tooltip dynamically.
 */
fun ActionButton.withTooltip(tip: String): JPanel {
    val wrapper = JPanel(BorderLayout())
    wrapper.isOpaque = false
    wrapper.toolTipText = tip
    wrapper.add(this, BorderLayout.CENTER)
    return wrapper
}
