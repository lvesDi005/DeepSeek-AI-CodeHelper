package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * API Configuration panel — provider selection and credentials for all supported providers.
 *
 * Changes are auto-saved to [DeepSeekSettings] on field focus loss or selection change.
 */
class ApiConfigPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var apiKeyField: JBPasswordField? = null
    private var modelField: JBTextField? = null
    private var maxTokensField: JBTextField? = null
    private var temperatureField: JBTextField? = null
    private var providerComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var agnesApiKeyField: JBPasswordField? = null
    private var agnesModelField: JBTextField? = null
    private var agnesBaseUrlField: JBTextField? = null
    private var nvidiaApiKeyField: JBPasswordField? = null
    private var nvidiaModelComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var nvidiaBaseUrlField: JBTextField? = null
    private var openrouterApiKeyField: JBPasswordField? = null
    private var openrouterModelComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var openrouterBaseUrlField: JBTextField? = null

    init {
        isOpaque = false

        val form = panel {
            group(I18n.tr("api.group.title")) {
                row(I18n.tr("api.provider")) {
                    providerComboBox = comboBox<String>(listOf("deepseek", "agnes", "nvidia", "openrouter"))
                        .apply {
                            component.selectedItem = settings.provider
                            component.addActionListener { saveSettings() }
                        }
                        .component
                    comment(I18n.tr("api.provider.comment"))
                }
                group(I18n.tr("api.deepseek.group")) {
                    row(I18n.tr("api.deepseek.key")) {
                        apiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.apiKey
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBPasswordField
                        comment("<a href='https://platform.deepseek.com/api_keys'>platform.deepseek.com</a>")
                    }
                    row(I18n.tr("api.deepseek.model")) {
                        modelField = cell(JBTextField().apply {
                            columns = 30
                            text = settings.model
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment(I18n.tr("api.deepseek.model.comment"))
                    }
                    row(I18n.tr("api.deepseek.max.tokens")) {
                        maxTokensField = cell(JBTextField().apply {
                            columns = 10
                            text = settings.maxTokens.toString()
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                    }
                    row(I18n.tr("api.deepseek.temperature")) {
                        temperatureField = cell(JBTextField().apply {
                            columns = 10
                            text = settings.temperature.toString()
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment(I18n.tr("api.deepseek.temperature.comment"))
                    }
                }
                group(I18n.tr("api.agnes.group")) {
                    row(I18n.tr("api.agnes.key")) {
                        agnesApiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.agnesApiKey
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBPasswordField
                        comment("<a href='https://platform.agnes-ai.com'>platform.agnes-ai.com</a>")
                    }
                    row(I18n.tr("api.agnes.model")) {
                        agnesModelField = cell(JBTextField().apply {
                            columns = 30
                            text = settings.agnesModel
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment(I18n.tr("api.agnes.model.comment"))
                    }
                    row(I18n.tr("api.agnes.base.url")) {
                        agnesBaseUrlField = cell(JBTextField().apply {
                            columns = 40
                            text = settings.agnesBaseUrl
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment("https://apihub.agnes-ai.com/v1")
                    }
                }
                group(I18n.tr("api.nvidia.group")) {
                    row(I18n.tr("api.nvidia.key")) {
                        nvidiaApiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.nvidiaApiKey
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBPasswordField
                        comment("<a href='https://build.nvidia.com/'>build.nvidia.com</a>")
                    }
                    row(I18n.tr("api.nvidia.model")) {
                        nvidiaModelComboBox = comboBox<String>(
                            listOf("z-ai/glm-5.2", "minimaxai/minimax-m3", "stepfun-ai/step-3.7-flash")
                        ).apply {
                            component.isEditable = true
                            component.selectedItem = settings.nvidiaModel
                            component.addActionListener { saveSettings() }
                        }.component
                        comment("<a href='https://build.nvidia.com/models'>build.nvidia.com/models</a>")
                    }
                    row(I18n.tr("api.nvidia.base.url")) {
                        nvidiaBaseUrlField = cell(JBTextField().apply {
                            columns = 40
                            text = settings.nvidiaBaseUrl
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment("https://integrate.api.nvidia.com/v1")
                    }
                }
                group(I18n.tr("api.openrouter.group")) {
                    row(I18n.tr("api.openrouter.key")) {
                        openrouterApiKeyField = cell(JBPasswordField().apply {
                            columns = 50
                            text = settings.openrouterApiKey
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBPasswordField
                        comment("<a href='https://openrouter.ai/keys'>openrouter.ai/keys</a>")
                    }
                    row(I18n.tr("api.openrouter.model")) {
                        openrouterModelComboBox = comboBox<String>(
                            listOf("inclusionai/ling-3.0-flash:free", "poolside/laguna-xs-2.1:free")
                        ).apply {
                            component.isEditable = true
                            component.selectedItem = settings.openrouterModel
                            component.addActionListener { saveSettings() }
                        }.component
                        comment(I18n.tr("api.openrouter.model.comment"))
                    }
                    row(I18n.tr("api.openrouter.base.url")) {
                        openrouterBaseUrlField = cell(JBTextField().apply {
                            columns = 40
                            text = settings.openrouterBaseUrl
                            font = JBUI.Fonts.label()
                            addFocusListener(object : FocusAdapter() {
                                override fun focusLost(e: FocusEvent) { saveSettings() }
                            })
                        }).component as JBTextField
                        comment("https://openrouter.ai/api/v1")
                    }
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    private fun saveSettings() {
        settings.apiKey = apiKeyField?.password?.let { String(it) } ?: ""
        settings.model = modelField?.text ?: "deepseek-v4-flash"
        settings.maxTokens = maxTokensField?.text?.toIntOrNull() ?: 4096
        settings.temperature = temperatureField?.text?.toDoubleOrNull() ?: 0.7
        settings.provider = providerComboBox?.selectedItem as? String ?: "deepseek"
        settings.agnesApiKey = agnesApiKeyField?.password?.let { String(it) } ?: ""
        settings.agnesModel = agnesModelField?.text ?: "Agnes-2.0-Flash"
        settings.agnesBaseUrl = agnesBaseUrlField?.text ?: "https://apihub.agnes-ai.com/v1"
        settings.nvidiaApiKey = nvidiaApiKeyField?.password?.let { String(it) } ?: ""
        settings.nvidiaModel = nvidiaModelComboBox?.selectedItem as? String ?: "z-ai/glm-5.2"
        settings.nvidiaBaseUrl = nvidiaBaseUrlField?.text ?: "https://integrate.api.nvidia.com/v1"
        settings.openrouterApiKey = openrouterApiKeyField?.password?.let { String(it) } ?: ""
        settings.openrouterModel = openrouterModelComboBox?.selectedItem as? String ?: "inclusionai/ling-3.0-flash:free"
        settings.openrouterBaseUrl = openrouterBaseUrlField?.text ?: "https://openrouter.ai/api/v1"
    }
}
