package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.mcp.service.McpServerService
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * Settings panel for MCP Server configuration.
 */
class McpSettingsPanel : JPanel() {

    private val settings = DeepSeekSettings.instance

    private val enableCheckBox = JCheckBox(I18n.tr("settings.mcp.enable"), settings.mcpEnabled)
    private val autoStartCheckBox = JCheckBox(I18n.tr("settings.mcp.auto_start"), settings.mcpAutoStart)
    private val portField = JBTextField(settings.mcpPort.toString())

    private val startStopButton = JButton(I18n.tr("settings.mcp.start"))

    private val statusLabel = JBLabel(I18n.tr("settings.mcp.status.stopped"))
    private val urlLabel = JBLabel("URL: -")
    private val sessionLabel = JBLabel("${I18n.tr("settings.mcp.sessions")} 0")
    private val toolsLabel = JBLabel("${I18n.tr("settings.mcp.tools")} 0")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(20, 20, 20, 20)
        background = JBColor(Color(0xF0F0F0), Color(0x3C3F41))

        val title = JBLabel(I18n.tr("settings.mcp.title"))
        title.font = title.font.deriveFont(Font.BOLD, 16f)
        title.alignmentX = Component.LEFT_ALIGNMENT
        title.border = JBUI.Borders.empty(0, 0, 16, 0)
        add(title)

        val desc = JBLabel("<html>${I18n.tr("settings.mcp.desc")}</html>")
        desc.alignmentX = Component.LEFT_ALIGNMENT
        desc.border = JBUI.Borders.empty(0, 0, 20, 0)
        add(desc)

        // Server Settings Group
        add(createGroupPanel(I18n.tr("settings.mcp.server"), listOf(
            createEnableRow(),
            createPortRow(),
            createAutoStartRow()
        )))

        add(Box.createVerticalStrut(16))

        // Status Group (with control buttons below)
        add(createStatusGroup())

        refreshStatus()
    }

    private fun createStatusGroup(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.border = CompoundBorder(
            JBUI.Borders.customLineBottom(JBColor(Color(0xD0D0D0), Color(0x555555))),
            JBUI.Borders.empty(0, 0, 12, 0)
        )

        val titleLabel = JBLabel(I18n.tr("settings.mcp.status"))
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        titleLabel.border = JBUI.Borders.empty(0, 0, 8, 0)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)

        panel.add(statusLabel)
        panel.add(urlLabel)
        panel.add(sessionLabel)
        panel.add(toolsLabel)

        panel.add(Box.createVerticalStrut(10))

        val btnRow = JPanel()
        btnRow.layout = BoxLayout(btnRow, BoxLayout.X_AXIS)
        btnRow.isOpaque = false
        btnRow.alignmentX = Component.LEFT_ALIGNMENT

        startStopButton.addActionListener {
            val service = McpServerService.getInstance()
            if (service.isRunning) {
                service.stopServer()
            } else {
                val port = portField.text.toIntOrNull()?.coerceIn(1, 65535) ?: 8080
                settings.mcpPort = port
                service.startServer(port)
            }
            refreshStatus()
        }
        btnRow.add(startStopButton)

        val refreshBtn = JButton(I18n.tr("settings.mcp.refresh"), AllIcons.Actions.Refresh)
        refreshBtn.addActionListener {
            McpServerService.getInstance().refreshTools()
            refreshStatus()
        }
        btnRow.add(Box.createHorizontalStrut(8))
        btnRow.add(refreshBtn)

        btnRow.add(Box.createHorizontalGlue())
        panel.add(btnRow)

        return panel
    }

    private fun createGroupPanel(title: String, rows: List<JPanel>): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.border = CompoundBorder(
            JBUI.Borders.customLineBottom(JBColor(Color(0xD0D0D0), Color(0x555555))),
            JBUI.Borders.empty(0, 0, 12, 0)
        )

        val titleLabel = JBLabel(title)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        titleLabel.border = JBUI.Borders.empty(0, 0, 8, 0)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(titleLabel)

        rows.forEach { row ->
            row.alignmentX = Component.LEFT_ALIGNMENT
            row.maximumSize = Dimension(Short.MAX_VALUE.toInt(), row.preferredSize.height)
            panel.add(row)
        }

        return panel
    }

    private fun createEnableRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false

        enableCheckBox.isOpaque = false
        enableCheckBox.addActionListener {
            settings.mcpEnabled = enableCheckBox.isSelected
            updateButtonState()
        }
        row.add(enableCheckBox)
        row.add(Box.createHorizontalGlue())

        return row
    }

    private fun createPortRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false
        row.border = JBUI.Borders.empty(4, 0, 4, 0)

        row.add(JBLabel(I18n.tr("settings.mcp.port")))
        row.add(Box.createHorizontalStrut(8))

        portField.columns = 8
        portField.maximumSize = Dimension(100, portField.preferredSize.height)
        row.add(portField)

        row.add(Box.createHorizontalStrut(8))
        row.add(JBLabel(I18n.tr("settings.mcp.port.comment")))
        row.add(Box.createHorizontalGlue())

        return row
    }

    private fun createAutoStartRow(): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.X_AXIS)
        row.isOpaque = false

        autoStartCheckBox.isOpaque = false
        autoStartCheckBox.addActionListener {
            settings.mcpAutoStart = autoStartCheckBox.isSelected
        }
        row.add(autoStartCheckBox)
        row.add(Box.createHorizontalGlue())

        return row
    }

    fun refreshStatus() {
        val service = McpServerService.getInstance()

        if (service.isRunning) {
            statusLabel.text = I18n.tr("settings.mcp.status.running")
            urlLabel.text = "URL: ${service.getSseUrl() ?: "-"}"
            sessionLabel.text = "${I18n.tr("settings.mcp.sessions")} ${service.sessionCount()}"
            startStopButton.text = I18n.tr("settings.mcp.stop")
        } else {
            statusLabel.text = I18n.tr("settings.mcp.status.stopped")
            urlLabel.text = "URL: -"
            sessionLabel.text = "${I18n.tr("settings.mcp.sessions")} 0"
            startStopButton.text = I18n.tr("settings.mcp.start")
        }

        toolsLabel.text = "${I18n.tr("settings.mcp.tools")} ${service.getTools().size}"
        updateButtonState()
    }

    private fun updateButtonState() {
        val service = McpServerService.getInstance()
        if (!service.isRunning) {
            startStopButton.isEnabled = enableCheckBox.isSelected
        }
    }
}
