package com.deepseek.plugin.ui

import com.deepseek.plugin.settings.readClaudeSettingsBaseUrl
import com.deepseek.plugin.settings.readClaudeSettingsEnvKey
import com.deepseek.plugin.settings.readCodexAuthApiKey
import com.deepseek.plugin.settings.readCodexAuthHasOAuthOnly
import com.deepseek.plugin.settings.readCodexConfigBaseUrl
import com.deepseek.plugin.settings.readCodexConfigModel
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel

/**
 * Claude / Codex 独立配置面板。
 *
 * 与 ApiConfigPanel 分离：本地凭据（~/.claude/settings.json、~/.codex/config.toml + auth.json）
 * 仅在用户显式点击授权按钮后读取并写入设置，避免未授权的凭据被发往 API 端点导致 403。
 */
class ClaudeCodexConfigPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var anthropicApiKeyField: JBPasswordField? = null
    private var anthropicModelComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var anthropicBaseUrlField: JBTextField? = null
    private var codexApiKeyField: JBPasswordField? = null
    private var codexModelComboBox: com.intellij.openapi.ui.ComboBox<String>? = null
    private var codexBaseUrlField: JBTextField? = null
    private var codexReasoningEffortComboBox: com.intellij.openapi.ui.ComboBox<String>? = null

    init {
        isOpaque = false

        val form = panel {
            group(I18n.tr("cc.claude.group")) {
                row(I18n.tr("cc.claude.use.local")) {
                    button(I18n.tr("cc.claude.use.settings.json")) {
                        importClaudeSettingsJson()
                    }
                    comment(I18n.tr("cc.claude.use.local.comment"))
                }
                row(I18n.tr("cc.claude.key")) {
                    anthropicApiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.anthropicApiKey
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBPasswordField
                    comment(I18n.tr("cc.claude.key.comment"))
                }
                row(I18n.tr("cc.claude.model")) {
                    anthropicModelComboBox = comboBox<String>(
                        listOf("claude-sonnet-4-5", "claude-opus-4-5", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022")
                    ).apply {
                        component.isEditable = true
                        component.selectedItem = settings.anthropicModel
                        component.addActionListener { saveSettings() }
                    }.component
                    comment(I18n.tr("api.anthropic.model.comment"))
                }
                row(I18n.tr("cc.claude.base.url")) {
                    anthropicBaseUrlField = cell(JBTextField().apply {
                        columns = 40
                        text = settings.anthropicBaseUrl
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment("https://api.anthropic.com/v1")
                }
            }
            group(I18n.tr("cc.codex.group")) {
                row(I18n.tr("cc.codex.use.local")) {
                    button(I18n.tr("cc.codex.use.local.config")) {
                        importCodexLocalConfig()
                    }
                    comment(I18n.tr("cc.codex.use.local.comment"))
                }
                row(I18n.tr("cc.codex.key")) {
                    codexApiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.codexApiKey
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBPasswordField
                    comment(I18n.tr("cc.codex.key.comment"))
                }
                row(I18n.tr("cc.codex.model")) {
                    codexModelComboBox = comboBox<String>(
                        listOf("gpt-5.2-codex", "gpt-5.1-codex", "gpt-5-codex", "gpt-4o")
                    ).apply {
                        component.isEditable = true
                        component.selectedItem = settings.codexModel.ifBlank { readCodexConfigModel() ?: "gpt-5.2-codex" }
                        component.addActionListener { saveSettings() }
                    }.component
                    comment(I18n.tr("cc.codex.model.comment"))
                }
                row(I18n.tr("cc.codex.base.url")) {
                    codexBaseUrlField = cell(JBTextField().apply {
                        columns = 40
                        text = settings.codexBaseUrl
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBTextField
                    comment("https://api.openai.com/v1")
                }
                row(I18n.tr("cc.codex.reasoning.effort")) {
                    codexReasoningEffortComboBox = comboBox(listOf("low", "medium", "high")).apply {
                        component.selectedItem = settings.codexReasoningEffort
                        component.addActionListener { saveSettings() }
                    }.component
                    comment(I18n.tr("cc.codex.reasoning.effort.comment"))
                }
            }
            group(I18n.tr("cli.mode.group")) {
                val permissionOptions = listOf("acceptEdits", "bypass", "plan")
                val permissionLabels = listOf(
                    I18n.tr("cli.mode.permission.acceptEdits"),
                    I18n.tr("cli.mode.permission.bypass"),
                    I18n.tr("cli.mode.permission.plan")
                )
                row(I18n.tr("cli.mode.permission.label")) {
                    comboBox(permissionLabels).apply {
                        component.selectedIndex =
                            permissionOptions.indexOf(settings.cliAgentPermissionMode).coerceAtLeast(0)
                        component.addActionListener {
                            val idx = component.selectedIndex
                            if (idx in permissionOptions.indices) {
                                settings.cliAgentPermissionMode = permissionOptions[idx]
                            }
                        }
                    }
                    comment(I18n.tr("cli.mode.permission.comment"))
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    /** 显式授权：读取 ~/.claude/settings.json 的 env 配置并写入设置 */
    private fun importClaudeSettingsJson() {
        val key = readClaudeSettingsEnvKey()
        if (key == null) {
            Messages.showWarningDialog(
                I18n.tr("cc.claude.use.not.found"),
                I18n.tr("cc.claude.use.title")
            )
            return
        }
        settings.anthropicApiKey = key
        readClaudeSettingsBaseUrl()?.let { settings.anthropicBaseUrl = it }
        anthropicApiKeyField?.text = key
        anthropicBaseUrlField?.text = settings.anthropicBaseUrl
        saveSettings()
        Messages.showInfoMessage(
            I18n.tr("cc.claude.use.success"),
            I18n.tr("cc.claude.use.title")
        )
    }

    /** 显式授权：读取 ~/.codex/config.toml + auth.json 并写入设置 */
    private fun importCodexLocalConfig() {
        val apiKey = readCodexAuthApiKey()
        if (apiKey == null) {
            val msg = if (readCodexAuthHasOAuthOnly()) {
                I18n.tr("cc.codex.use.oauth.only")
            } else {
                I18n.tr("cc.codex.use.not.found")
            }
            Messages.showWarningDialog(msg, I18n.tr("cc.codex.use.title"))
            return
        }
        settings.codexApiKey = apiKey
        readCodexConfigModel()?.let { settings.codexModel = it }
        readCodexConfigBaseUrl()?.let {
            settings.codexBaseUrl = it
            codexBaseUrlField?.text = it
        }
        codexApiKeyField?.text = apiKey
        codexModelComboBox?.selectedItem = settings.codexModel
        saveSettings()
        Messages.showInfoMessage(
            I18n.tr("cc.codex.use.success"),
            I18n.tr("cc.codex.use.title")
        )
    }

    private fun saveSettings() {
        settings.anthropicApiKey = anthropicApiKeyField?.password?.let { String(it) } ?: ""
        settings.anthropicModel = anthropicModelComboBox?.selectedItem as? String ?: "claude-sonnet-4-5"
        settings.anthropicBaseUrl = anthropicBaseUrlField?.text ?: "https://api.anthropic.com/v1"
        settings.codexApiKey = codexApiKeyField?.password?.let { String(it) } ?: ""
        settings.codexModel = codexModelComboBox?.selectedItem as? String ?: ""
        settings.codexBaseUrl = codexBaseUrlField?.text ?: "https://api.openai.com/v1"
        settings.codexReasoningEffort = codexReasoningEffortComboBox?.selectedItem as? String ?: "medium"
    }
}
