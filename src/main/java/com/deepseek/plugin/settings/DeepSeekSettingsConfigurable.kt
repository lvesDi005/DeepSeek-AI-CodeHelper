package com.deepseek.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
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
    private var stepFunApiKeyField: JBPasswordField? = null
    private var stepFunModelField: JBTextField? = null
    private var providerComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agnesApiKeyField: JBPasswordField? = null
    private var agnesModelField: JBTextField? = null
    private var agnesBaseUrlField: JBTextField? = null

    override fun getDisplayName(): String = "DeepSeek AI CodeHelper"

    override fun createComponent(): JComponent {
        return panel {
            group("API Configuration") {
                row("API Provider:") {
                    providerComboBox = comboBox<String>(listOf("deepseek", "agnes"))
                        .apply { component.selectedItem = settings.provider }
                        .component
                    comment("deepseek = DeepSeek API, agnes = Agnes 2.0 Flash")
                }
                row("API Key:") {
                    apiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.apiKey
                    }).component as JBPasswordField
                    comment("DeepSeek: <a href='https://platform.deepseek.com/api_keys'>platform.deepseek.com</a>")
                }
                row("Model:") {
                    modelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.model
                    }).component as JBTextField
                    comment("deepseek-v4-flash or deepseek-v4-pro")
                }
                row("Max Tokens:") {
                    maxTokensField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.maxTokens.toString()
                    }).component as JBTextField
                }
                row("Temperature:") {
                    temperatureField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.temperature.toString()
                    }).component as JBTextField
                    comment("0.0 - 2.0")
                }
            }

            group("Agnes 2.0 Flash") {
                row("API Key:") {
                    agnesApiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.agnesApiKey
                    }).component as JBPasswordField
                    comment("Get key at <a href='https://platform.agnes-ai.com'>platform.agnes-ai.com</a>")
                }
                row("Model:") {
                    agnesModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.agnesModel
                    }).component as JBTextField
                    comment("agnes-2.0-flash")
                }
                row("Base URL:") {
                    agnesBaseUrlField = cell(JBTextField().apply {
                        columns = 40
                        text = settings.agnesBaseUrl
                    }).component as JBTextField
                    comment("https://apihub.agnes-ai.com/v1")
                }
            }

            group("Code Completion") {
                row {
                    completionEnabledCheckbox = checkBox("Enable AI code completion")
                        .apply { component.isSelected = settings.completionEnabled }
                        .component
                }
                row("Completion Model:") {
                    completionModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.completionModel
                    }).component as JBTextField
                    comment("Leave empty to use main model above. Try: deepseek-v4-flash")
                }
                row("Max Completion Tokens:") {
                    completionMaxTokensField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionMaxTokens.toString()
                    }).component as JBTextField
                    comment("Max tokens per completion suggestion")
                }
                row("Min Prefix Chars:") {
                    completionMinPrefixField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionMinPrefix.toString()
                    }).component as JBTextField
                    comment("Minimum characters typed before triggering completion (default: 2)")
                }
                row("Max Context Lines:") {
                    maxContextLinesField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.maxContextLines.toString()
                    }).component as JBTextField
                    comment("Lines of surrounding code sent to the model for context")
                }
                row("Debounce Delay (ms):") {
                    completionDelayField = cell(JBTextField().apply {
                        columns = 10
                        text = settings.completionDelayMs.toString()
                    }).component as JBTextField
                }
            }

            group("StepFun Image Parsing") {
                row("API Key:") {
                    stepFunApiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.stepFunApiKey
                    }).component as JBPasswordField
                    comment("Get your key at <a href='https://platform.stepfun.com'>platform.stepfun.com</a>")
                }
                row("Model:") {
                    stepFunModelField = cell(JBTextField().apply {
                        columns = 30
                        text = settings.stepFunModel
                    }).component as JBTextField
                    comment("step-1o-turbo-vision (vision language model)")
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
                || stepFunApiKeyField?.password?.let { String(it) } != settings.stepFunApiKey
                || stepFunModelField?.text != settings.stepFunModel
                || providerComboBox?.selectedItem != settings.provider
                || agnesApiKeyField?.password?.let { String(it) } != settings.agnesApiKey
                || agnesModelField?.text != settings.agnesModel
                || agnesBaseUrlField?.text != settings.agnesBaseUrl
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
        settings.stepFunApiKey = stepFunApiKeyField?.password?.let { String(it) } ?: ""
        settings.stepFunModel = stepFunModelField?.text ?: "step-1o-turbo-vision"
        settings.provider = providerComboBox?.selectedItem as? String ?: "deepseek"
        settings.agnesApiKey = agnesApiKeyField?.password?.let { String(it) } ?: ""
        settings.agnesModel = agnesModelField?.text ?: "Agnes-2.0-Flash"
        settings.agnesBaseUrl = agnesBaseUrlField?.text ?: "https://apihub.agnes-ai.com/v1"
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
        stepFunApiKeyField?.text = settings.stepFunApiKey
        stepFunModelField?.text = settings.stepFunModel
        providerComboBox?.selectedItem = settings.provider
        agnesApiKeyField?.text = settings.agnesApiKey
        agnesModelField?.text = settings.agnesModel
        agnesBaseUrlField?.text = settings.agnesBaseUrl
    }
}
