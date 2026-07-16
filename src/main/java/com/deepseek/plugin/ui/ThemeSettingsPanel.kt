package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.ide.ui.LafManager
import com.intellij.ui.JBColor
import javax.swing.UIManager
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Theme settings panel — left-aligned layout with flat theme buttons.
 */
@Suppress("DEPRECATION")
class ThemeSettingsPanel : JPanel(BorderLayout()) {

    private val settings = DeepSeekSettings.instance
    private var selectedTheme: String = detectCurrentTheme()
    private val themeCards = mutableListOf<JPanel>()

    init {
        I18n.currentLang = settings.language

        isOpaque = false
        border = JBUI.Borders.empty(20, 20, 20, 20)

        // ── Title ──
        val titleLabel = JLabel(I18n.tr("settings.theme.title")).apply {
            font = JBUI.Fonts.label().asBold()
            foreground = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // ── Flat theme buttons, left-aligned ──
        val followBtn = createFlatButton(
            title = I18n.tr("settings.theme.follow"),
            icon = createFollowIcon(),
            themeKey = "follow"
        )
        val darkBtn = createFlatButton(
            title = I18n.tr("settings.theme.dark"),
            icon = createDarkIcon(),
            themeKey = "dark"
        )
        val lightBtn = createFlatButton(
            title = I18n.tr("settings.theme.light"),
            icon = createLightIcon(),
            themeKey = "light"
        )
        themeCards.addAll(listOf(followBtn, darkBtn, lightBtn))

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(followBtn)
            add(darkBtn)
            add(lightBtn)
        }

        // ── Language selector (same level as title) ──
        val langRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val langs = listOf("zh" to "中文", "en" to "English")
            val combo = JComboBox(langs.map { it.second }.toTypedArray()).apply {
                selectedIndex = langs.indexOfFirst { it.first == settings.language }.coerceAtLeast(0)
                addActionListener {
                    val idx = selectedIndex
                    if (idx in langs.indices) {
                        val lang = langs[idx].first
                        settings.language = lang
                        I18n.currentLang = lang
                        JOptionPane.showMessageDialog(
                            this@ThemeSettingsPanel,
                            if (lang == "en") "Language switched. Please close and reopen the settings panel to apply."
                            else "语言已切换，请关闭并重新打开设置面板以生效。",
                            if (lang == "en") "Language Changed" else "语言已切换",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    }
                }
            }
            add(JLabel(I18n.tr("lang.label")).apply {
                font = JBUI.Fonts.label()
                foreground = JBColor(Color(0x555555), Color(0xAAAAAA))
            })
            add(combo)
        }

        // ── Agent 输出语言选择器（与 UI 语言隔离）──
        val agentLangRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val langs = listOf("zh" to "中文", "en" to "English")
            val combo = JComboBox(langs.map { it.second }.toTypedArray()).apply {
                selectedIndex = langs.indexOfFirst { it.first == settings.aiLanguage }.coerceAtLeast(0)
                addActionListener {
                    val idx = selectedIndex
                    if (idx in langs.indices) {
                        settings.aiLanguage = langs[idx].first
                    }
                }
            }
            add(JLabel("  ${I18n.tr("lang.agent.label")}").apply {
                font = JBUI.Fonts.label()
                foreground = JBColor(Color(0x555555), Color(0xAAAAAA))
            })
            add(combo)
        }

        // ── Stack vertically, left-aligned ──
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(Box.createVerticalStrut(16))
            add(btnRow)
            add(Box.createVerticalStrut(20))
            add(langRow)
            add(Box.createVerticalStrut(8))
            add(agentLangRow)
        }
        add(body, BorderLayout.NORTH)
    }

    /**
     * Create a flat theme button (no card border, simple background on select).
     */
    private fun createFlatButton(title: String, icon: Icon, themeKey: String): JPanel {
        val btn = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (selectedTheme == themeKey) {
                    g2.color = JBColor(Color(0xE3F0FF), Color(0x3A4A6A))
                    g2.fillRoundRect(0, 0, width, height, 6, 6)
                }
                g2.dispose()
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            preferredSize = Dimension(72, 64)
            minimumSize = Dimension(72, 64)
            maximumSize = Dimension(72, 64)
            border = JBUI.Borders.empty(8, 12, 8, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    applyTheme(themeKey)
                    selectedTheme = themeKey
                    themeCards.forEach { it.repaint() }
                }
            })
        }

        btn.add(Box.createVerticalGlue())
        btn.add(JLabel(icon).apply { alignmentX = Component.CENTER_ALIGNMENT })
        btn.add(Box.createVerticalStrut(4))
        btn.add(JLabel(title).apply {
            font = JBUI.Fonts.smallFont().asBold()
            foreground = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))
            alignmentX = Component.CENTER_ALIGNMENT
        })
        btn.add(Box.createVerticalGlue())

        return btn
    }

    private fun applyTheme(themeKey: String) {
        val lafManager = LafManager.getInstance()
        val installedLafs = lafManager.installedLookAndFeels
        val target = when (themeKey) {
            "follow" -> installedLafs.find { it.name == "IntelliJ" } ?: installedLafs.firstOrNull()
            "dark" -> installedLafs.find {
                it.name.contains("Dark", true) || it.name.contains("Darcula", true) || it.name.contains("One Dark", true)
            }
            "light" -> installedLafs.find { it.name == "IntelliJ" }
                ?: installedLafs.find { it.name.contains("Light", true) }
                ?: installedLafs.find { !it.name.contains("Darcula", true) }
            else -> null
        } ?: return

        applyThemeByReflection(lafManager, target)
        lafManager.updateUI()
    }

    /**
     * Set the L&F via reflection, trying API variants in order:
     * 1. 2025.x: setCurrentLookAndFeel(UIThemeLookAndFeelInfo, boolean)
     * 2. 2024.3: setCurrentLookAndFeel(LookAndFeel)
     * 3. Fallback: UIManager.setLookAndFeel(className)
     */

    private fun applyThemeByReflection(lafManager: Any, target: Any) {
        // --- Attempt 1: new 2025.x API ---
        try {
            val themeInfoClass = Class.forName("com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo")
            val method = LafManager::class.java.getMethod("setCurrentLookAndFeel", themeInfoClass, Boolean::class.javaPrimitiveType)
            method.invoke(lafManager, target, false)
            return
        } catch (_: NoSuchMethodException) {
        } catch (_: ClassNotFoundException) {
        }

        // --- Attempt 2: old 2024.3 API ---
        try {
            val method = LafManager::class.java.getMethod("setCurrentLookAndFeel", javax.swing.LookAndFeel::class.java)
            method.invoke(lafManager, target)
            return
        } catch (_: NoSuchMethodException) {
        } catch (_: ClassCastException) {
            // target is UIThemeLookAndFeelInfo, not LookAndFeel — fall through
        }

        // --- Attempt 3: UIManager with class name ---
        val className = try {
            // UIThemeLookAndFeelInfo.getClassName()
            target::class.java.getMethod("getClassName").invoke(target) as String
        } catch (_: NoSuchMethodException) {
            // fallback for LookAndFeel: javaClass.name
            target.javaClass.name
        }
        UIManager.setLookAndFeel(className)
    }

    private fun detectCurrentTheme(): String {
        val lafName = UIManager.getLookAndFeel().name ?: ""
        return when {
            lafName.contains("Darcula", true) || lafName.contains("One Dark", true) -> "dark"
            lafName.equals("Dark", true) || lafName.startsWith("Dark", true) -> "dark"
            else -> "light"
        }
    }

    // ── Icon helpers (16×16) ──

    private fun createFollowIcon(): Icon = object : Icon {
        override fun getIconWidth() = 16; override fun getIconHeight() = 16
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBColor(Color(0x555555), Color(0xAAAAAA))
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.drawOval(x + 3, y + 3, 10, 10); g2.fillOval(x + 5, y + 5, 6, 6)
            for (angle in 0 until 360 step 45) { val rad = Math.toRadians(angle.toDouble()); val r1 = 7.0; val r2 = 9.0
                g2.drawLine((x + 8 + r1 * Math.cos(rad)).toInt(), (y + 8 + r1 * Math.sin(rad)).toInt(), (x + 8 + r2 * Math.cos(rad)).toInt(), (y + 8 + r2 * Math.sin(rad)).toInt()) }
            g2.dispose()
        }
    }

    private fun createDarkIcon(): Icon = object : Icon {
        override fun getIconWidth() = 16; override fun getIconHeight() = 16
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0x3C3F41); g2.fillRoundRect(x, y, 16, 16, 4, 4)
            g2.color = Color(0xF0E68C); g2.fillOval(x + 3, y + 3, 10, 10); g2.color = Color(0x3C3F41); g2.fillOval(x + 7, y + 2, 8, 8)
            g2.dispose()
        }
    }

    private fun createLightIcon(): Icon = object : Icon {
        override fun getIconWidth() = 16; override fun getIconHeight() = 16
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D; g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Color(0xFFFFFF); g2.fillRoundRect(x, y, 16, 16, 4, 4)
            g2.color = Color(0xE8A800); g2.fillOval(x + 4, y + 4, 8, 8)
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (angle in 0 until 360 step 45) { val rad = Math.toRadians(angle.toDouble()); val r1 = 6.0; val r2 = 8.0
                g2.drawLine((x + 8 + r1 * Math.cos(rad)).toInt(), (y + 8 + r1 * Math.sin(rad)).toInt(), (x + 8 + r2 * Math.cos(rad)).toInt(), (y + 8 + r2 * Math.sin(rad)).toInt()) }
            g2.dispose()
        }
    }
}
