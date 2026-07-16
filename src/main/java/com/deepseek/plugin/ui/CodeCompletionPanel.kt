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
 * Code Completion configuration panel.
 *
 * Changes are auto-saved to [DeepSeekSettings] on field focus loss or checkbox toggle.
 */
class CodeCompletionPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var completionModelField: JBTextField? = null
    private var completionMaxTokensField: JBTextField? = null
    private var completionDelayField: JBTextField? = null
    private var completionMinPrefixField: JBTextField? = null
    private var maxContextLinesField: JBTextField? = null
    private var completionEnabledCheckbox: JCheckBox? = null
    private var annotationAwareCheckbox: JCheckBox? = null
    private var commentAwareCheckbox: JCheckBox? = null
    private var cacheEnabledCheckbox: JCheckBox? = null
    private var statusBarCheckbox: JCheckBox? = null
    private var ghostTextCheckbox: JCheckBox? = null

    init {
        isOpaque = false

        val form = panel {
            group(I18n.tr("completion.group.title")) {
                row {
                    completionEnabledCheckbox = checkBox(I18n.tr("completion.enable"))
                        .apply {
                            component.isSelected = settings.completionEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                }
                row(I18n.tr("completion.model")) {
                    completionModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.completionModel
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("completion.model.comment"))
                }
                row(I18n.tr("completion.max.tokens")) {
                    completionMaxTokensField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionMaxTokens.toString()
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("completion.max.tokens.comment"))
                }
                row(I18n.tr("completion.min.prefix")) {
                    completionMinPrefixField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionMinPrefix.toString()
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("completion.min.prefix.comment"))
                }
                row(I18n.tr("completion.max.context")) {
                    maxContextLinesField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.maxContextLines.toString()
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("completion.max.context.comment"))
                }
                row(I18n.tr("completion.debounce")) {
                    completionDelayField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionDelayMs.toString()
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                }
                row {
                    annotationAwareCheckbox = checkBox(I18n.tr("completion.annotation"))
                        .apply {
                            component.isSelected = settings.annotationAwareEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("completion.annotation.comment"))
                }
                row {
                    commentAwareCheckbox = checkBox(I18n.tr("completion.comment"))
                        .apply {
                            component.isSelected = settings.commentAwareEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("completion.comment.comment"))
                }
                row {
                    cacheEnabledCheckbox = checkBox(I18n.tr("completion.cache"))
                        .apply {
                            component.isSelected = settings.completionCacheEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("completion.cache.comment"))
                }
                row {
                    statusBarCheckbox = checkBox(I18n.tr("completion.statusbar"))
                        .apply {
                            component.isSelected = settings.completionStatusBarEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("completion.statusbar.comment"))
                }
                row {
                    ghostTextCheckbox = checkBox(I18n.tr("completion.ghost"))
                        .apply {
                            component.isSelected = settings.completionGhostTextEnabled
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("completion.ghost.comment"))
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    private fun saveSettings() {
        settings.completionEnabled = completionEnabledCheckbox?.isSelected ?: true
        settings.completionModel = completionModelField?.text ?: ""
        settings.completionMaxTokens = completionMaxTokensField?.text?.toIntOrNull() ?: 256
        settings.completionMinPrefix = completionMinPrefixField?.text?.toIntOrNull() ?: 2
        settings.maxContextLines = maxContextLinesField?.text?.toIntOrNull() ?: 30
        settings.completionDelayMs = completionDelayField?.text?.toLongOrNull() ?: 500
        settings.annotationAwareEnabled = annotationAwareCheckbox?.isSelected ?: true
        settings.commentAwareEnabled = commentAwareCheckbox?.isSelected ?: true
        settings.completionCacheEnabled = cacheEnabledCheckbox?.isSelected ?: true
        settings.completionStatusBarEnabled = statusBarCheckbox?.isSelected ?: true
        settings.completionGhostTextEnabled = ghostTextCheckbox?.isSelected ?: false
    }
}
