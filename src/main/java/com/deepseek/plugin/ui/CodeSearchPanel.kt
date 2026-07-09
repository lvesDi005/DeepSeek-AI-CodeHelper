package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JCheckBox
import javax.swing.JPanel

/**
 * Code Search (Agentic Search) configuration panel.
 *
 * Changes are auto-saved to [DeepSeekSettings] on checkbox toggle or field focus loss.
 */
class CodeSearchPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var agenticSearchEnabledCheckbox: JCheckBox? = null
    private var agenticSearchRoundsField: JBTextField? = null

    init {
        isOpaque = false

        val form = panel {
            group(I18n.tr("search.group.title")) {
                row {
                    agenticSearchEnabledCheckbox = checkBox(I18n.tr("search.enable"))
                        .apply {
                            component.isSelected = settings.agenticSearchEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("search.enable.comment"))
                }
                row(I18n.tr("search.rounds")) {
                    agenticSearchRoundsField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.agenticSearchMaxRounds.toString()
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("search.rounds.comment"))
                }
                row {
                    comment(I18n.tr("search.comment1"))
                }
                row {
                    comment(I18n.tr("search.comment2"))
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    private fun saveSettings() {
        settings.agenticSearchEnabled = agenticSearchEnabledCheckbox?.isSelected ?: true
        settings.agenticSearchMaxRounds = agenticSearchRoundsField?.text?.toIntOrNull() ?: 3
    }
}
