package com.deepseek.plugin.ui

import com.deepseek.plugin.api.LlmProviderRegistry
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
 * The Q&A Classifier reuses the main API Configuration, so it does not appear here.
 * Changes are auto-saved to [DeepSeekSettings] on combo selection change or field focus loss.
 */
class AgentPipelinePanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private val providerIds = LlmProviderRegistry.allProviders().map { it.id }.toList()

    // ── Agent Pipeline 4 阶段绑定 ──
    private data class PhaseBinding(
        val providerLabelKey: String,
        val modelLabelKey: String,
        val modelCommentKey: String,
        val providerCombo: ComboBox<String>,
        val modelField: JBTextField,
        val providerGet: () -> String,
        val providerSet: (String) -> Unit,
        val modelGet: () -> String,
        val modelSet: (String) -> Unit
    )

    private val phaseBindings = mutableListOf<PhaseBinding>()

    init {
        isOpaque = false

        // 4 阶段的配置元数据（label key + settings 读写）
        data class PhaseMeta(
            val labelPrefix: String,
            val providerGet: () -> String,
            val providerSet: (String) -> Unit,
            val modelGet: () -> String,
            val modelSet: (String) -> Unit
        )

        val phaseMetas = listOf(
            PhaseMeta("pipeline.phase0",
                { settings.agentPhase0Provider }, { settings.agentPhase0Provider = it },
                { settings.agentPhase0Model }, { settings.agentPhase0Model = it }),
            PhaseMeta("pipeline.phase1",
                { settings.agentPhase1Provider }, { settings.agentPhase1Provider = it },
                { settings.agentPhase1Model }, { settings.agentPhase1Model = it }),
            PhaseMeta("pipeline.phase2",
                { settings.agentPhase2Provider }, { settings.agentPhase2Provider = it },
                { settings.agentPhase2Model }, { settings.agentPhase2Model = it }),
            PhaseMeta("pipeline.phase3",
                { settings.agentPhase3Provider }, { settings.agentPhase3Provider = it },
                { settings.agentPhase3Model }, { settings.agentPhase3Model = it })
        )

        val form = panel {
            // ════════════════════════════════════════════════════════════
            //  Agent Pipeline 4 阶段 — 通过循环动态生成
            // ════════════════════════════════════════════════════════════
            group(I18n.tr("pipeline.group.title")) {
                for (meta in phaseMetas) {
                    val providerLabelKey = "${meta.labelPrefix}.provider"
                    val modelLabelKey = "${meta.labelPrefix}.model"
                    val modelCommentKey = "${meta.labelPrefix}.model.comment"

                    // Provider 下拉框
                    lateinit var providerCombo: ComboBox<String>
                    row(I18n.tr(providerLabelKey)) {
                        providerCombo = comboBox<String>(providerIds)
                            .apply {
                                component.selectedItem = meta.providerGet()
                                component.addActionListener { saveSettings() }
                            }
                            .component
                    }

                    // Model 文本输入框
                    lateinit var modelField: JBTextField
                    row(I18n.tr(modelLabelKey)) {
                        modelField = cell(JBTextField().apply {
                            columns = 30
                            text = meta.modelGet()
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment(I18n.tr(modelCommentKey))
                    }

                    phaseBindings.add(PhaseBinding(
                        providerLabelKey = providerLabelKey,
                        modelLabelKey = modelLabelKey,
                        modelCommentKey = modelCommentKey,
                        providerCombo = providerCombo,
                        modelField = modelField,
                        providerGet = meta.providerGet,
                        providerSet = meta.providerSet,
                        modelGet = meta.modelGet,
                        modelSet = meta.modelSet
                    ))
                }

                // 底部说明 + 查看模型按钮
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
        for (binding in phaseBindings) {
            binding.providerSet(binding.providerCombo.selectedItem as? String ?: binding.providerGet())
            binding.modelSet(binding.modelField.text ?: binding.modelGet())
        }
    }

}
