package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel

/**
 * Agent Pipeline configuration panel — each phase can use a different Provider+Model.
 *
 * Changes are auto-saved to [DeepSeekSettings] on combo selection change or field focus loss.
 */
class AgentPipelinePanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var agentPhase0ProviderComboBox: ComboBox<String>? = null
    private var agentPhase0ModelField: JBTextField? = null
    private var agentPhase1ProviderComboBox: ComboBox<String>? = null
    private var agentPhase1ModelField: JBTextField? = null
    private var agentPhase2ProviderComboBox: ComboBox<String>? = null
    private var agentPhase2ModelField: JBTextField? = null
    private var agentPhase3ProviderComboBox: ComboBox<String>? = null
    private var agentPhase3ModelField: JBTextField? = null

    init {
        isOpaque = false

        val form = panel {
            group(I18n.tr("pipeline.group.title")) {
                row(I18n.tr("pipeline.phase0.provider")) {
                    agentPhase0ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia", "openrouter"))
                        .apply {
                            component.selectedItem = settings.agentPhase0Provider
                            component.addActionListener { saveSettings() }
                        }
                        .component
                }
                row(I18n.tr("pipeline.phase0.model")) {
                    agentPhase0ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase0Model
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("pipeline.phase0.model.comment"))
                }
                row(I18n.tr("pipeline.phase1.provider")) {
                    agentPhase1ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia", "openrouter"))
                        .apply {
                            component.selectedItem = settings.agentPhase1Provider
                            component.addActionListener { saveSettings() }
                        }
                        .component
                }
                row(I18n.tr("pipeline.phase1.model")) {
                    agentPhase1ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase1Model
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("pipeline.phase1.model.comment"))
                }
                row(I18n.tr("pipeline.phase2.provider")) {
                    agentPhase2ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia", "openrouter"))
                        .apply {
                            component.selectedItem = settings.agentPhase2Provider
                            component.addActionListener { saveSettings() }
                        }
                        .component
                }
                row(I18n.tr("pipeline.phase2.model")) {
                    agentPhase2ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase2Model
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("pipeline.phase2.model.comment"))
                }
                row(I18n.tr("pipeline.phase3.provider")) {
                    agentPhase3ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia", "openrouter"))
                        .apply {
                            component.selectedItem = settings.agentPhase3Provider
                            component.addActionListener { saveSettings() }
                        }
                        .component
                }
                row(I18n.tr("pipeline.phase3.model")) {
                    agentPhase3ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase3Model
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment(I18n.tr("pipeline.phase3.model.comment"))
                }
                row {
                    comment(I18n.tr("pipeline.comment"))
                }
                row {
                    button(I18n.tr("pipeline.view.models")) {
                        ModelCatalogDialog().show()
                    }.apply {
                        component.putClientProperty("JButton.minimumWidth", 200)
                    }
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    private fun saveSettings() {
        settings.agentPhase0Provider = agentPhase0ProviderComboBox?.selectedItem as? String ?: "agnes"
        settings.agentPhase0Model = agentPhase0ModelField?.text ?: "agnes-2.0-flash"
        settings.agentPhase1Provider = agentPhase1ProviderComboBox?.selectedItem as? String ?: "deepseek"
        settings.agentPhase1Model = agentPhase1ModelField?.text ?: "deepseek-v4-pro"
        settings.agentPhase2Provider = agentPhase2ProviderComboBox?.selectedItem as? String ?: "deepseek"
        settings.agentPhase2Model = agentPhase2ModelField?.text ?: "deepseek-v4-flash"
        settings.agentPhase3Provider = agentPhase3ProviderComboBox?.selectedItem as? String ?: "agnes"
        settings.agentPhase3Model = agentPhase3ModelField?.text ?: "agnes-2.0-flash"
    }
}
