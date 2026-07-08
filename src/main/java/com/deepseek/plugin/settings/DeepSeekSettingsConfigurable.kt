package com.deepseek.plugin.settings

import com.deepseek.plugin.ui.ModelCatalogDialog
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import javax.swing.JCheckBox
import javax.swing.JComponent

class DeepSeekSettingsConfigurable : Configurable {
    private val settings = DeepSeekSettings.instance
    private var apiKeyField: JBPasswordField? = null
    private var modelField: JBTextField? = null
    private var maxTokensField: JBTextField? = null
    private var temperatureField: JBTextField? = null
    private var completionModelField: JBTextField? = null
    private var completionMaxTokensField: JBTextField? = null
    private var completionDelayField: JBTextField? = null
    private var completionMinPrefixField: JBTextField? = null
    private var maxContextLinesField: JBTextField? = null
    private var completionEnabledCheckbox: JCheckBox? = null
    private var annotationAwareCheckbox: JCheckBox? = null
    private var commentAwareCheckbox: JCheckBox? = null
    private var imageParsingModelComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var stepFunApiKeyField: JBPasswordField? = null
    private var providerComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agnesApiKeyField: JBPasswordField? = null
    private var agnesModelField: JBTextField? = null
    private var agnesBaseUrlField: JBTextField? = null
    private var nvidiaApiKeyField: JBPasswordField? = null
    private var nvidiaModelComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var nvidiaBaseUrlField: JBTextField? = null
    private var agenticSearchEnabledCheckbox: JCheckBox? = null
    private var agenticSearchRoundsField: JBTextField? = null
    private var agentPhase0ProviderComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agentPhase0ModelField: JBTextField? = null
    private var agentPhase1ProviderComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agentPhase1ModelField: JBTextField? = null
    private var agentPhase2ProviderComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agentPhase2ModelField: JBTextField? = null
    private var agentPhase3ProviderComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agentPhase3ModelField: JBTextField? = null

    override fun getDisplayName(): String = "DeepSeek AI CodeHelper"

    override fun createComponent(): JComponent {
        return panel {
            group("API Configuration (API 设置)") {
                row("API Provider:") {
                    providerComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia"))
                        .apply { component.selectedItem = settings.provider }
                        .component
                    comment("deepseek = DeepSeek API, agnes = Agnes 2.0 Flash, nvidia = NVIDIA NIM")
                }
                group("DeepSeek") {
                    row("API Key:") {
                        apiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.apiKey
                            font = JBUI.Fonts.label()
                        }).component as JBPasswordField
                        comment("<a href='https://platform.deepseek.com/api_keys'>platform.deepseek.com</a>")
                    }
                    row("Model:") {
                        modelField = cell(JBTextField().apply {
                            columns = 30
                            text = settings.model
                            font = JBUI.Fonts.label()
                        }).component as JBTextField
                        comment("deepseek-v4-flash or deepseek-v4-pro")
                    }
                    row("Max Tokens:") {
                        maxTokensField = cell(JBTextField().apply {
                            columns = 10
                            text = settings.maxTokens.toString()
                            font = JBUI.Fonts.label()
                        }).component as JBTextField
                    }
                    row("Temperature:") {
                        temperatureField = cell(JBTextField().apply {
                            columns = 10
                            text = settings.temperature.toString()
                            font = JBUI.Fonts.label()
                        }).component as JBTextField
                        comment("0.0 - 2.0")
                    }
                }
                group("Agnes 2.0 Flash") {
                    row("API Key:") {
                        agnesApiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.agnesApiKey
                            font = JBUI.Fonts.label()
                        }).component as JBPasswordField
                        comment("<a href='https://platform.agnes-ai.com'>platform.agnes-ai.com</a>")
                    }
                    row("Model:") {
                        agnesModelField = cell(JBTextField().apply {
                            columns = 30
                            text = settings.agnesModel
                            font = JBUI.Fonts.label()
                        }).component as JBTextField
                        comment("agnes-2.0-flash")
                    }
                    row("Base URL:") {
                        agnesBaseUrlField = cell(JBTextField().apply {
                            columns = 40
                            text = settings.agnesBaseUrl
                            font = JBUI.Fonts.label()
                        }).component as JBTextField
                        comment("https://apihub.agnes-ai.com/v1")
                    }
                }
                group("NVIDIA Model") {
                    row("API Key:") {
                        nvidiaApiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.nvidiaApiKey
                            font = JBUI.Fonts.label()
                        }).component as JBPasswordField
                        comment("<a href='https://build.nvidia.com/'>build.nvidia.com</a>")
                    }
                    row("Model:") {
                        nvidiaModelComboBox = comboBox<String>(
                            listOf("z-ai/glm-5.2", "minimaxai/minimax-m3", "stepfun-ai/step-3.7-flash")
                        ).apply {
                            component.selectedItem = settings.nvidiaModel
                        }.component
                        comment("z-ai/glm-5.2 or minimaxai/minimax-m3 or stepfun-ai/step-3.7-flash")
                    }
                    row("Base URL:") {
                        nvidiaBaseUrlField = cell(JBTextField().apply {
                            columns = 40
                            text = settings.nvidiaBaseUrl
                            font = JBUI.Fonts.label()
                        }).component as JBTextField
                        comment("https://integrate.api.nvidia.com/v1")  
                    }
                }
            }

            group("Code Completion (代码补全)") {
                row {
                    completionEnabledCheckbox = checkBox("Enable AI code completion")
                        .apply { component.isSelected = settings.completionEnabled }
                        .component
                }
                row("Completion Model:") {
                    completionModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.completionModel
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Leave empty to use main model above. Try: deepseek-v4-flash")
                }
                row("Max Completion Tokens:") {
                    completionMaxTokensField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionMaxTokens.toString()
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Max tokens per completion suggestion")
                }
                row("Min Prefix Chars:") {
                    completionMinPrefixField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionMinPrefix.toString()
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Minimum characters typed before triggering completion (default: 2)")
                }
                row("Max Context Lines:") {
                    maxContextLinesField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.maxContextLines.toString()
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Lines of surrounding code sent to the model for context")
                }
                row("Debounce Delay (ms):") {
                    completionDelayField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionDelayMs.toString()
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                }
                row {
                    annotationAwareCheckbox = checkBox("Annotation-aware completion (analyze @Data, @Service, @Entity, etc.)")
                        .apply { component.isSelected = settings.annotationAwareEnabled }
                        .component
                    comment("Automatically suggests synthetic methods (Lombok), code patterns (Spring/JPA), and annotation names")
                }
                row {
                    commentAwareCheckbox = checkBox("Comment-aware completion (analyze // getter, // singleton, // TODO, etc.)")
                        .apply { component.isSelected = settings.commentAwareEnabled }
                        .component
                    comment("Generates code based on natural language comments: // getter for name, // save, // singleton pattern, etc.")
                }
            }

            group("Agentic Search (代码搜索)") {
                row {
                    agenticSearchEnabledCheckbox = checkBox("Enable Agentic Search (as an alternative to RAG for code retrieval)")
                        .apply { component.isSelected = settings.agenticSearchEnabled }
                        .component
                    comment("Once enabled, code search uses grep/glob/read tools instead of BM25 vector retrieval, making results more accurate and real-time.")
                }
                row("Maximum search rounds:") {
                    agenticSearchRoundsField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.agenticSearchMaxRounds.toString()
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("1 = Single-round mode (fast), 2~5 = Multi-round mode (the model can iterate and search on its own, more accurate but a bit slower)")
                }
                row {
                    comment("Agentic Search lets models 'search → read → judge → then search again' like human programmers, making it great for situations where you need precise code matching.")
                }
                row {
                    comment("RAG is still used for document text retrieval (.md/.txt, etc.), and the two complement each other.")
                }
            }

            group("Image Parsing (图像解析)") {
                row("Image Parsing Model:") {
                    imageParsingModelComboBox = comboBox<String>(
                        listOf("Agnes Image 2.1 Flash", "StepFun")
                    ).apply {
                        component.selectedItem = when (settings.imageParsingModel) {
                            "stepfun" -> "StepFun"
                            else -> "Agnes Image 2.1 Flash"
                        }
                    }.component
                    comment("Select the model used for parsing images in chat")
                }
                row("StepFun API Key:") {
                    stepFunApiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.stepFunApiKey
                        font = JBUI.Fonts.label()
                    }).component as JBPasswordField
                    comment("<a href='https://platform.stepfun.com/'>platform.stepfun.com</a>")
                }
                row {
                    comment("Agnes reuses the API key configured in the API Configuration section above.")
                }
            }
            group("Agent Pipeline (Agent 流水线配置)") {
                row("Phase 0 意图确认 Provider:") {
                    agentPhase0ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia"))
                        .apply { component.selectedItem = settings.agentPhase0Provider }
                        .component
                }
                row("Phase 0 意图确认 Model:") {
                    agentPhase0ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase0Model
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Default: agnes-2.0-flash")
                }
                row("Phase 1 规划 Provider:") {
                    agentPhase1ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia"))
                        .apply { component.selectedItem = settings.agentPhase1Provider }
                        .component
                }
                row("Phase 1 规划 Model:") {
                    agentPhase1ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase1Model
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Default: deepseek-v4-pro")
                }
                row("Phase 2 编码 Provider:") {
                    agentPhase2ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia"))
                        .apply { component.selectedItem = settings.agentPhase2Provider }
                        .component
                }
                row("Phase 2 编码 Model:") {
                    agentPhase2ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase2Model
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Default: deepseek-v4-flash")
                }
                row("Phase 3 审查 Provider:") {
                    agentPhase3ProviderComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia"))
                        .apply { component.selectedItem = settings.agentPhase3Provider }
                        .component
                }
                row("Phase 3 审查 Model:") {
                    agentPhase3ModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agentPhase3Model
                        font = JBUI.Fonts.label()
                    }).component as JBTextField
                    comment("Default: agnes-2.0-flash")
                }
                row {
                    comment("Each phase can use a different Provider+Model. The API Key comes from the selected Provider's configuration above.")
                }
                row {
                    button("View Models / 查看模型") {
                        ModelCatalogDialog().show()
                    }.apply {
                        component.putClientProperty("JButton.minimumWidth", 200)
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        return apiKeyField?.password?.let { String(it) } != settings.apiKey
                || modelField?.text != settings.model
                || maxTokensField?.text?.toIntOrNull() != settings.maxTokens
                || temperatureField?.text?.toDoubleOrNull() != settings.temperature
                || completionEnabledCheckbox?.isSelected != settings.completionEnabled
                || completionModelField?.text != settings.completionModel
                || completionMaxTokensField?.text?.toIntOrNull() != settings.completionMaxTokens
                || completionMinPrefixField?.text?.toIntOrNull() != settings.completionMinPrefix
                || maxContextLinesField?.text?.toIntOrNull() != settings.maxContextLines
                || completionDelayField?.text?.toLongOrNull() != settings.completionDelayMs
                || imageParsingModelComboBox?.selectedItem != modelIdToDisplayName(settings.imageParsingModel)
                || stepFunApiKeyField?.password?.let { String(it) } != settings.stepFunApiKey
                || providerComboBox?.selectedItem != settings.provider
                || agnesApiKeyField?.password?.let { String(it) } != settings.agnesApiKey
                || agnesModelField?.text != settings.agnesModel
                || agnesBaseUrlField?.text != settings.agnesBaseUrl
                || annotationAwareCheckbox?.isSelected != settings.annotationAwareEnabled
                || commentAwareCheckbox?.isSelected != settings.commentAwareEnabled
                || agenticSearchEnabledCheckbox?.isSelected != settings.agenticSearchEnabled
                || agenticSearchRoundsField?.text?.toIntOrNull() != settings.agenticSearchMaxRounds
                || nvidiaApiKeyField?.password?.let { String(it) } != settings.nvidiaApiKey
                || nvidiaModelComboBox?.selectedItem != settings.nvidiaModel
                || nvidiaBaseUrlField?.text != settings.nvidiaBaseUrl
                || agentPhase0ProviderComboBox?.selectedItem != settings.agentPhase0Provider
                || agentPhase0ModelField?.text != settings.agentPhase0Model
                || agentPhase1ProviderComboBox?.selectedItem != settings.agentPhase1Provider
                || agentPhase1ModelField?.text != settings.agentPhase1Model
                || agentPhase2ProviderComboBox?.selectedItem != settings.agentPhase2Provider
                || agentPhase2ModelField?.text != settings.agentPhase2Model
                || agentPhase3ProviderComboBox?.selectedItem != settings.agentPhase3Provider
                || agentPhase3ModelField?.text != settings.agentPhase3Model
    }

    override fun apply() {
        settings.apiKey = apiKeyField?.password?.let { String(it) } ?: ""
        settings.model = modelField?.text ?: "deepseek-v4-flash"
        settings.maxTokens = maxTokensField?.text?.toIntOrNull() ?: 4096
        settings.temperature = temperatureField?.text?.toDoubleOrNull() ?: 0.7
        settings.completionEnabled = completionEnabledCheckbox?.isSelected ?: true
        settings.completionModel = completionModelField?.text ?: ""
        settings.completionMaxTokens = completionMaxTokensField?.text?.toIntOrNull() ?: 256
        settings.completionMinPrefix = completionMinPrefixField?.text?.toIntOrNull() ?: 2
        settings.maxContextLines = maxContextLinesField?.text?.toIntOrNull() ?: 30
        settings.completionDelayMs = completionDelayField?.text?.toLongOrNull() ?: 500
        settings.imageParsingModel = modelIdFromDisplayName(imageParsingModelComboBox?.selectedItem as? String ?: "Agnes Image 2.1 Flash")
        settings.stepFunApiKey = stepFunApiKeyField?.password?.let { String(it) } ?: ""
        settings.provider = providerComboBox?.selectedItem as? String ?: "deepseek"
        settings.agnesApiKey = agnesApiKeyField?.password?.let { String(it) } ?: ""
        settings.agnesModel = agnesModelField?.text ?: "Agnes-2.0-Flash"
        settings.agnesBaseUrl = agnesBaseUrlField?.text ?: "https://apihub.agnes-ai.com/v1"
        settings.annotationAwareEnabled = annotationAwareCheckbox?.isSelected ?: true
        settings.commentAwareEnabled = commentAwareCheckbox?.isSelected ?: true
        settings.agenticSearchEnabled = agenticSearchEnabledCheckbox?.isSelected ?: true
        settings.agenticSearchMaxRounds = agenticSearchRoundsField?.text?.toIntOrNull() ?: 3
        settings.nvidiaApiKey = nvidiaApiKeyField?.password?.let { String(it) } ?: ""
        settings.nvidiaModel = nvidiaModelComboBox?.selectedItem as? String ?: "z-ai/glm-5.2"
        settings.nvidiaBaseUrl = nvidiaBaseUrlField?.text ?: "https://integrate.api.nvidia.com/v1"
        settings.agentPhase0Provider = agentPhase0ProviderComboBox?.selectedItem as? String ?: "agnes"
        settings.agentPhase0Model = agentPhase0ModelField?.text ?: "agnes-2.0-flash"
        settings.agentPhase1Provider = agentPhase1ProviderComboBox?.selectedItem as? String ?: "deepseek"
        settings.agentPhase1Model = agentPhase1ModelField?.text ?: "deepseek-v4-pro"
        settings.agentPhase2Provider = agentPhase2ProviderComboBox?.selectedItem as? String ?: "deepseek"
        settings.agentPhase2Model = agentPhase2ModelField?.text ?: "deepseek-v4-flash"
        settings.agentPhase3Provider = agentPhase3ProviderComboBox?.selectedItem as? String ?: "agnes"
        settings.agentPhase3Model = agentPhase3ModelField?.text ?: "agnes-2.0-flash"
    }

    override fun reset() {
        apiKeyField?.text = settings.apiKey
        modelField?.text = settings.model
        maxTokensField?.text = settings.maxTokens.toString()
        temperatureField?.text = settings.temperature.toString()
        completionEnabledCheckbox?.isSelected = settings.completionEnabled
        completionModelField?.text = settings.completionModel
        completionMaxTokensField?.text = settings.completionMaxTokens.toString()
        completionMinPrefixField?.text = settings.completionMinPrefix.toString()
        maxContextLinesField?.text = settings.maxContextLines.toString()
        completionDelayField?.text = settings.completionDelayMs.toString()
        annotationAwareCheckbox?.isSelected = settings.annotationAwareEnabled
        commentAwareCheckbox?.isSelected = settings.commentAwareEnabled
        agenticSearchEnabledCheckbox?.isSelected = settings.agenticSearchEnabled
        agenticSearchRoundsField?.text = settings.agenticSearchMaxRounds.toString()
        stepFunApiKeyField?.text = settings.stepFunApiKey
        imageParsingModelComboBox?.selectedItem = when (settings.imageParsingModel) {
            "stepfun" -> "StepFun"
            else -> "Agnes Image 2.1 Flash"
        }
        providerComboBox?.selectedItem = settings.provider
        agnesApiKeyField?.text = settings.agnesApiKey
        agnesModelField?.text = settings.agnesModel
        agnesBaseUrlField?.text = settings.agnesBaseUrl
        nvidiaApiKeyField?.text = settings.nvidiaApiKey
        nvidiaModelComboBox?.selectedItem = settings.nvidiaModel
        nvidiaBaseUrlField?.text = settings.nvidiaBaseUrl
        agentPhase0ProviderComboBox?.selectedItem = settings.agentPhase0Provider
        agentPhase0ModelField?.text = settings.agentPhase0Model
        agentPhase1ProviderComboBox?.selectedItem = settings.agentPhase1Provider
        agentPhase1ModelField?.text = settings.agentPhase1Model
        agentPhase2ProviderComboBox?.selectedItem = settings.agentPhase2Provider
        agentPhase2ModelField?.text = settings.agentPhase2Model
        agentPhase3ProviderComboBox?.selectedItem = settings.agentPhase3Provider
        agentPhase3ModelField?.text = settings.agentPhase3Model
    }

    // ── Image parsing model display name ↔ model ID helpers ──

    private fun modelIdToDisplayName(id: String): String = when (id) {
        "stepfun" -> "StepFun"
        else -> "Agnes Image 2.1 Flash"
    }

    private fun modelIdFromDisplayName(displayName: String): String = when (displayName) {
        "StepFun" -> "stepfun"
        else -> "agnes"
    }
}
