package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.mcp.client.*
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * Settings panel for configuring external MCP server connections.
 */
class ExternalMcpSettingsPanel : JPanel() {

    private val store = ExternalMcpStore.getInstance()
    private val manager = ExternalMcpManager.getInstance()

    private val cardPanel = JPanel(CardLayout())
    private val listPanel = JPanel()
    private val addButton = JButton(I18n.tr("settings.mcp.external.add"), AllIcons.General.Add)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(20, 20, 20, 20)

        val title = JBLabel(I18n.tr("settings.mcp.external.title"))
        title.font = title.font.deriveFont(Font.BOLD, 16f)
        title.alignmentX = Component.LEFT_ALIGNMENT
        title.border = JBUI.Borders.empty(0, 0, 4, 0)
        add(title)

        val desc = JBLabel("<html>${I18n.tr("settings.mcp.external.desc")}</html>")
        desc.alignmentX = Component.LEFT_ALIGNMENT
        desc.border = JBUI.Borders.empty(0, 0, 12, 0)
        add(desc)

        listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
        listPanel.isOpaque = false

        val scrollPane = JBScrollPane(listPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }

        refreshList()

        add(scrollPane)
        add(Box.createVerticalStrut(12))
        add(addButton.also { it.alignmentX = Component.LEFT_ALIGNMENT })
        addButton.addActionListener { showAddDialog(null) }
    }

    private fun refreshList() {
        listPanel.removeAll()
        val servers = store.servers

        if (servers.isEmpty()) {
            val emptyLabel = JBLabel(I18n.tr("settings.mcp.external.empty"))
            emptyLabel.border = JBUI.Borders.empty(20, 0)
            emptyLabel.alignmentX = Component.LEFT_ALIGNMENT
            listPanel.add(emptyLabel)
        } else {
            for (config in servers) {
                listPanel.add(createServerCard(config))
                listPanel.add(Box.createVerticalStrut(8))
            }
        }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun createServerCard(config: ExternalMcpConfig): JPanel {
        val card = JPanel()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.isOpaque = false
        card.border = CompoundBorder(
            BorderFactory.createLineBorder(JBColor(Color(0xD0D0D0), Color(0x555555)), 1),
            JBUI.Borders.empty(12, 16, 12, 16)
        )
        card.alignmentX = Component.LEFT_ALIGNMENT
        card.maximumSize = Dimension(Short.MAX_VALUE.toInt(), card.preferredSize.height)

        // Row 1: Name + Type + Status + remove button
        val topRow = JPanel(BorderLayout())
        topRow.isOpaque = false

        val nameLabel = JBLabel(config.name)
        nameLabel.font = nameLabel.font.deriveFont(Font.BOLD, 13f)
        topRow.add(nameLabel, BorderLayout.WEST)

        val centerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0))
        centerPanel.isOpaque = false

        val typeLabel = JBLabel("[${config.transportType}]")
        typeLabel.foreground = JBColor(Color(0x888888), Color(0x888888))
        centerPanel.add(typeLabel)

        val isConn = manager.isConnected(config.name)
        val statusLabel = JBLabel(if (isConn) I18n.tr("settings.mcp.external.connected") else I18n.tr("settings.mcp.external.disconnected"))
        statusLabel.foreground = if (isConn) Color(0x2E7D32) else Color(0x888888)
        centerPanel.add(statusLabel)

        topRow.add(centerPanel, BorderLayout.CENTER)

        val removeBtn = JButton(AllIcons.Actions.Close)
        removeBtn.preferredSize = Dimension(24, 24)
        removeBtn.isBorderPainted = false
        removeBtn.isContentAreaFilled = false
        removeBtn.addActionListener {
            val confirm = JOptionPane.showConfirmDialog(
                this, "${I18n.tr("settings.mcp.external.remove")} '${config.name}'?",
                I18n.tr("settings.mcp.external.remove"), JOptionPane.YES_NO_OPTION
            )
            if (confirm == JOptionPane.YES_OPTION) {
                manager.disconnect(config.name)
                store.servers.removeAll { it.name == config.name }
                refreshList()
            }
        }
        topRow.add(removeBtn, BorderLayout.EAST)

        card.add(topRow)

        // Row 2: URL/Command
        val urlRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        urlRow.isOpaque = false
        urlRow.add(JBLabel("  ${config.url}").also {
            it.foreground = JBColor(Color(0x555555), Color(0xAAAAAA))
            it.font = it.font.deriveFont(Font.PLAIN, 11f)
        })
        card.add(urlRow)

        // Row 3: Tools count
        val tools = manager.getAllTools().filter { it.server == config.name }
        if (tools.isNotEmpty()) {
            val toolsRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
            toolsRow.isOpaque = false
            toolsRow.add(JBLabel("  ${I18n.tr("settings.mcp.external.tools")} ${tools.map { it.originalName }.joinToString(", ")}").also {
                it.foreground = JBColor(Color(0x555555), Color(0xAAAAAA))
                it.font = it.font.deriveFont(Font.PLAIN, 11f)
            })
            card.add(toolsRow)
        }

        // Row 4: Action buttons
        val actionRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        actionRow.isOpaque = false

        val connectBtn = JButton(if (isConn) I18n.tr("settings.mcp.external.disconnect") else I18n.tr("settings.mcp.external.connect"))
        connectBtn.addActionListener {
            if (isConn) {
                manager.disconnect(config.name)
            } else {
                manager.connect(config)
            }
            refreshList()
        }
        actionRow.add(connectBtn)

        val editBtn = JButton(I18n.tr("settings.mcp.external.edit"))
        editBtn.addActionListener { showAddDialog(config) }
        actionRow.add(editBtn)

        if (isConn) {
            val refreshToolsBtn = JButton(I18n.tr("settings.mcp.external.refresh_tools"))
            refreshToolsBtn.addActionListener {
                manager.refreshAllTools()
                refreshList()
            }
            actionRow.add(refreshToolsBtn)
        }

        card.add(actionRow)

        return card
    }

    private fun showAddDialog(existing: ExternalMcpConfig?) {
        val isEdit = existing != null
        val nameField = JBTextField(existing?.name ?: "")
        val typeCombo = JComboBox(TransportDisplay.values().map { it.label }.toTypedArray()).apply {
            if (existing != null) {
                selectedIndex = TransportDisplay.values().indexOfFirst { it.key == existing.transportType }.coerceAtLeast(0)
            }
        }
        val urlField = JBTextField(existing?.url ?: "")
        val argsField = JBTextField(existing?.args ?: "")
        val autoStartCheck = JCheckBox(I18n.tr("settings.mcp.external.auto_start"), existing?.autoStart ?: true)

        val panel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(10, 10, 10, 10)
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(4, 4, 4, 4)
                anchor = GridBagConstraints.WEST
            }

            gbc.gridx = 0; gbc.gridy = 0
            add(JLabel(I18n.tr("settings.mcp.external.name")), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0
            add(nameField, gbc)
            gbc.weightx = 0.0

            gbc.gridx = 0; gbc.gridy = 1
            add(JLabel(I18n.tr("settings.mcp.external.type")), gbc)
            gbc.gridx = 1
            add(typeCombo, gbc)

            gbc.gridx = 0; gbc.gridy = 2
            add(JLabel(I18n.tr("settings.mcp.external.url")), gbc)
            gbc.gridx = 1
            add(urlField, gbc)

            gbc.gridx = 0; gbc.gridy = 3
            add(JLabel(I18n.tr("settings.mcp.external.args")), gbc)
            gbc.gridx = 1
            add(argsField, gbc)

            gbc.gridx = 0; gbc.gridy = 4
            add(JLabel(""), gbc)
            gbc.gridx = 1
            add(autoStartCheck, gbc)
        }

        val options = arrayOf(
            if (isEdit) I18n.tr("settings.mcp.external.save") else I18n.tr("settings.mcp.external.test_save"),
            I18n.tr("settings.mcp.external.cancel")
        )

        val result = JOptionPane.showOptionDialog(
            this, panel,
            if (isEdit) I18n.tr("settings.mcp.external.edit_title") else I18n.tr("settings.mcp.external.add_title"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE, null,
            options, null
        )

        if (result != 0) return

        val name = nameField.text.trim()
        val transportType = TransportDisplay.values()[typeCombo.selectedIndex].key
        val url = urlField.text.trim()
        val args = argsField.text.trim()

        if (name.isBlank() || url.isBlank()) {
            JOptionPane.showMessageDialog(this,
                if (I18n.currentLang == "zh") "名称和 URL/命令为必填项。" else "Name and URL/Command are required.",
                "Error", JOptionPane.ERROR_MESSAGE)
            return
        }

        val newConfig = ExternalMcpConfig(
            name = name,
            transportType = transportType,
            url = url,
            args = args,
            enabled = true,
            autoStart = autoStartCheck.isSelected
        )

        // Test connection
        val testConn = McpClientConnection(newConfig)
        val connected = testConn.connect()
        if (connected) {
            testConn.disconnect()
        } else {
            val msg = testConn.statusMessage
            val warningMsg = if (I18n.currentLang == "zh") "连接测试失败: $msg\n\n仍然保存？" else "Connection test failed: $msg\n\nSave anyway?"
            val retry = JOptionPane.showConfirmDialog(this, warningMsg, "Connection Test", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
            if (retry != JOptionPane.YES_OPTION) return
        }

        if (isEdit) {
            val idx = store.servers.indexOfFirst { it.name == existing.name }
            if (idx >= 0) {
                manager.disconnect(existing.name)
                store.servers[idx] = newConfig
            }
        } else {
            if (store.servers.any { it.name == name }) {
                val dupMsg = if (I18n.currentLang == "zh") "服务器名称 '$name' 已存在。" else "A server with name '$name' already exists."
                JOptionPane.showMessageDialog(this, dupMsg, "Error", JOptionPane.ERROR_MESSAGE)
                return
            }
            store.servers.add(newConfig)
        }

        if (newConfig.enabled && newConfig.autoStart) {
            manager.connect(newConfig)
        }

        refreshList()
    }
}
