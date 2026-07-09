package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel

/**
 * Image Parsing configuration panel.
 *
 * Changes are auto-saved to [DeepSeekSettings] on combo selection change or field focus loss.
 */
class ImageParsingPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var imageParsingModelComboBox: ComboBox<String>? = null
    private var stepFunApiKeyField: JBPasswordField? = null

    init {
        isOpaque = false

        val form = panel {
            group(I18n.tr("image.group.title")) {
                row(I18n.tr("image.model")) {
                    imageParsingModelComboBox = comboBox<String>(
                        listOf(I18n.tr("image.model.agnes"), I18n.tr("image.model.stepfun"))
                    ).apply {
                        component.selectedItem = when (settings.imageParsingModel) {
                            "stepfun" -> I18n.tr("image.model.stepfun")
                            else -> I18n.tr("image.model.agnes")
                        }
                        component.addActionListener { saveSettings() }
                    }.component
                    comment(I18n.tr("image.model.comment"))
                }
                row(I18n.tr("image.stepfun.key")) {
                    stepFunApiKeyField = cell(JBPasswordField().apply {
                        columns = 50
                        text = settings.stepFunApiKey
                        font = JBUI.Fonts.label()
                        addFocusListener(object : FocusAdapter() {
                            override fun focusLost(e: FocusEvent) { saveSettings() }
                        })
                    }).component as JBPasswordField
                    comment("<a href='https://platform.stepfun.com/'>platform.stepfun.com</a>")
                }
                row {
                    comment(I18n.tr("image.agnes.comment"))
                }
            }
        }

        add(form, BorderLayout.CENTER)
    }

    private fun saveSettings() {
        settings.imageParsingModel = when (imageParsingModelComboBox?.selectedItem as? String) {
            "StepFun" -> "stepfun"
            else -> "agnes"
        }
        settings.stepFunApiKey = stepFunApiKeyField?.password?.let { String(it) } ?: ""
    }
}
