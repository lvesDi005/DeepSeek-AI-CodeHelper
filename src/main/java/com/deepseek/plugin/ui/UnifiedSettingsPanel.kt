package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.openapi.project.Project
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * Unified settings panel with an icon navigation bar at the top
 * and CardLayout-based sub-pages below.
 *
 * Layout:
 * ┌──────────────────────────────────────────────────┐
 * │ [⚙] [🔧] [▶] [💡] [🔍] [👁]            [✕]    │  ← navbar (fixed)
 * ├──────────────────────────────────────────────────┤
 * │                                                  │
 * │         CardLayout content (natural size)        │
 * │                                                  │
 * └──────────────────────────────────────────────────┘
 *
 * The content area reports the currently visible panel's preferred size,
 * so the overall panel grows/shrinks to fully display all content without
 * internal scrollbars.
 *
 * @param project   The current IntelliJ project.
 * @param onClose   Called when the user closes this settings panel to return to chat.
 */
class UnifiedSettingsPanel(
    private val project: Project,
    private val onClose: () -> Unit
) : JPanel() {

    private val navButtons = mutableMapOf<String, JButton>()
    private var activeNavKey: String = ""

    /** CardLayout content panel that reports the current card's preferred size. */
    private val contentPanel = object : JPanel(CardLayout()) {
        override fun getPreferredSize(): Dimension {
            for (comp in components) {
                if (comp.isVisible) {
                    return comp.preferredSize
                }
            }
            return super.getPreferredSize()
        }
    }

    /** The embedded skill settings panel — exposed for [getEnabledSkillsContent]. */
    private val skillSettingsPanel: SkillSettingsPanel

    init {
        background = JBColor(Color(0xF0F0F0), Color(0x3C3F41))
        border = JBUI.Borders.empty()
        layout = BoxLayout(this, BoxLayout.Y_AXIS)

        // ── Navigation bar (fixed) ──
        val navBar = createNavBar()
        navBar.maximumSize = Dimension(Short.MAX_VALUE.toInt(), navBar.preferredSize.height)
        navBar.alignmentX = Component.LEFT_ALIGNMENT
        add(navBar)

        // ── Content area: horizontal scroll only, vertical uses natural size ──
        contentPanel.isOpaque = false

        // Theme Settings page
        contentPanel.add(ThemeSettingsPanel(), "themeSettings")

        // Skill Settings page (embedded without its own header)
        skillSettingsPanel = SkillSettingsPanel(project, onClose = {}, showHeader = false)
        contentPanel.add(skillSettingsPanel, "skillSettings")

        // Real settings panels for each section
        contentPanel.add(ApiConfigPanel(), "apiConfig")
        contentPanel.add(AgentPipelinePanel(), "agentPipeline")
        contentPanel.add(CodeCompletionPanel(), "codeCompletion")
        contentPanel.add(CodeSearchPanel(), "codeSearch")
        contentPanel.add(ImageParsingPanel(), "imageParsing")

        // MCP Server settings
        contentPanel.add(McpSettingsPanel(), "mcpServer")

        // External MCP Servers
        contentPanel.add(ExternalMcpSettingsPanel(), "externalMcp")

        val contentScrollPane = JBScrollPane(contentPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(contentScrollPane)

        // Show theme settings by default
        showPage("themeSettings")
    }

    /**
     * Create the top navigation bar with icon buttons for each settings section.
     */
    private fun createNavBar(): JPanel {
        val navBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = CompoundBorder(
                JBUI.Borders.empty(6, 12, 6, 12),
                JBUI.Borders.customLineBottom(JBColor(Color(0xD0D0D0), Color(0x555555)))
            )
        }

        // Left side: navigation icon row
        val iconRow = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
        }

        data class NavItem(val key: String, val icon: Icon, val tooltip: String)

        val navItems = listOf(
            NavItem("themeSettings", AllIcons.Actions.Checked, I18n.tr("settings.theme")),
            NavItem("skillSettings", AllIcons.General.Settings, I18n.tr("settings.skills")),
            NavItem("apiConfig", AllIcons.General.ExternalTools, I18n.tr("settings.api.config")),
            NavItem("agentPipeline", AllIcons.Actions.Execute, I18n.tr("settings.agent.pipeline")),
            NavItem("codeCompletion", AllIcons.Actions.IntentionBulb, I18n.tr("settings.code.completion")),
            NavItem("codeSearch", AllIcons.Actions.Find, I18n.tr("settings.code.search")),
            NavItem("imageParsing", AllIcons.Actions.Preview, I18n.tr("settings.image.parsing")),
            NavItem("mcpServer", AllIcons.Nodes.Plugin, I18n.tr("settings.mcp.server")),
            NavItem("externalMcp", AllIcons.General.Web, I18n.tr("settings.mcp.external"))
        )

        for (item in navItems) {
            val btn = createNavButton(item.icon, item.tooltip) {
                showPage(item.key)
            }
            navButtons[item.key] = btn
            iconRow.add(btn)
        }

        navBar.add(iconRow, BorderLayout.WEST)

        // Right side: close button
        val closeBtn = createToolbarButton(
            icon = AllIcons.Actions.Close,
            tooltip = I18n.tr("settings.close"),
            onClick = onClose
        )
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(closeBtn)
        }
        navBar.add(rightPanel, BorderLayout.EAST)

        return navBar
    }

    /**
     * Create a single navigation button with hover effect.
     */
    private fun createNavButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton {
        val dim = Dimension(JBUI.scale(32), JBUI.scale(32))
        return object : JButton(icon) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (model.isRollover || model.isSelected) {
                    g2.color = JBColor(0xE0E0E0, 0x4A4A4A)
                    g2.fillRoundRect(0, 0, width, height, 8, 8)
                }
                super.paintComponent(g)
                g2.dispose()
            }
        }.apply {
            toolTipText = tooltip
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = dim
            minimumSize = dim
            maximumSize = dim
            addActionListener { onClick() }
            isSelected = false
        }
    }

    /**
     * Switch to the settings page identified by [pageKey].
     */
    fun showPage(pageKey: String) {
        val cardLayout = contentPanel.layout as CardLayout
        cardLayout.show(contentPanel, pageKey)
        // Update active nav icon highlight
        navButtons.forEach { (key, btn) ->
            btn.isSelected = (key == pageKey)
        }
        activeNavKey = pageKey
        // Revalidate so the parent respects the new preferred size
        revalidate()
        repaint()
    }

    /**
     * Get all currently enabled skills' content as a combined string.
     * Delegates to the embedded [SkillSettingsPanel].
     *
     * @param userMessage 用戶當前問題，用於按需過濾相關技能。空字符串時全量注入。
     */
    fun getEnabledSkillsContent(userMessage: String = ""): String {
        return skillSettingsPanel.getEnabledSkillsContent(userMessage)
    }
}
