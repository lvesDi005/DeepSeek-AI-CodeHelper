package com.deepseek.plugin.ui

import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT
import com.deepseek.plugin.api.HttpClientProvider
import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.settings.DeepSeekSettings
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.border.AbstractBorder

/**
 * 翻译对话框 — 主题自适应双栏翻译界面。
 *
 * ┌───────────────────────────────────────────────┐
 * │  ┌──────────────┐    ┌──────────────┐         │
 * │  │ 中文 ▾       │ ⇄  │ English ▾    │         │
 * │  │              │    │              │         │
 * │  │ 输入文本...   │    │ 翻译...      │         │
 * │  │              │    │              │         │
 * │  └──────────────┘    └──────────────┘         │
 * │              [ 翻  译 ]                       │
 * ├───────────────────────────────────────────────┤
 * │                              [OK] [Cancel]    │
 * └───────────────────────────────────────────────┘
 */
class TranslateDialog(project: Project?) : DialogWrapper(project, false) {

    // ── 语言列表 ──
    private val languages = listOf(
        I18n.tr("lang.zh"), I18n.tr("lang.en"), I18n.tr("lang.ja"), I18n.tr("lang.ko"), I18n.tr("lang.fr"),
        I18n.tr("lang.de"), I18n.tr("lang.es"), I18n.tr("lang.ru"), I18n.tr("lang.pt"), I18n.tr("lang.it")
    )

    // ── UI 组件 ──
    private val leftLangCombo = JComboBox(languages.toTypedArray()).apply {
        selectedIndex = 0
    }
    private val rightLangCombo = JComboBox(languages.toTypedArray()).apply {
        selectedIndex = 1
    }

    private val leftTextArea = ThemeAwareTextArea(I18n.tr("translate.placeholder.source"))
    private val rightTextArea = ThemeAwareTextArea(I18n.tr("translate.placeholder.target"))

    init {
        title = I18n.tr("translate.title")
        isResizable = true
        isModal = false
        init()
    }

    // ================================================================
    // 主面板
    // ================================================================

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout()).apply {
            background = JBColor(0xE8E8E8, 0x2B2B2B)
            border = JBUI.Borders.empty(14, 16, 18, 16)
            preferredSize = Dimension(660, 380)
        }

        // ── 双栏主体 ──
        val body = JPanel(GridBagLayout()).apply {
            background = JBColor(0xE8E8E8, 0x2B2B2B)
        }

        val c = GridBagConstraints()
        c.gridy = 0
        c.fill = GridBagConstraints.BOTH
        c.weighty = 1.0

        // 左栏
        c.gridx = 0
        c.weightx = 1.0
        c.insets = JBUI.insets(0)
        body.add(createInputCard(leftLangCombo, leftTextArea), c)

        // ⇄ 互换箭头
        c.gridx = 1
        c.fill = GridBagConstraints.NONE
        c.weightx = 0.0
        c.weighty = 0.0
        c.insets = JBUI.insets(0, 5, 0, 5)
        c.anchor = GridBagConstraints.CENTER
        body.add(createSwapButton(), c)

        // 右栏
        c.gridx = 2
        c.fill = GridBagConstraints.BOTH
        c.weightx = 1.0
        c.weighty = 1.0
        c.insets = JBUI.insets(0)
        c.anchor = GridBagConstraints.CENTER
        body.add(createInputCard(rightLangCombo, rightTextArea), c)

        root.add(body, BorderLayout.CENTER)

        // ── 底部「翻译」按钮 ──
        val bottomWrap = JPanel(BorderLayout()).apply {
            background = JBColor(0xE8E8E8, 0x2B2B2B)
            border = JBUI.Borders.empty(10, 0, 0, 0)
        }
        translateBtn = JButton(I18n.tr("translate.button")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor(0x333333, 0xCCCCCC)
            background = JBColor(0xE0E0E0, 0x4E4E50)
            isOpaque = true
            isContentAreaFilled = true
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = JBUI.Borders.empty(8, 32)
            addActionListener { performTranslation() }
        }
        val btnCenter = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            background = JBColor(0xE8E8E8, 0x2B2B2B)
            add(translateBtn)
        }
        bottomWrap.add(btnCenter, BorderLayout.CENTER)
        root.add(bottomWrap, BorderLayout.SOUTH)

        return root
    }

    // ================================================================
    // 输入卡片 — 圆角面板
    // ================================================================

    private fun createInputCard(langCombo: JComboBox<String>, textArea: ThemeAwareTextArea): JPanel {
        val card = object : JPanel(BorderLayout(0, 0)) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(0xFFFFFF, 0x3C3F41)
                g2.fillRoundRect(0, 0, width, height, 4, 4)
                g2.dispose()
            }
        }.apply {
            isOpaque = true
            border = JBUI.Borders.empty(0)
        }

        // 语言下拉
        styleDropdown(langCombo)
        card.add(langCombo, BorderLayout.NORTH)

        // 文本区域
        val scrollPane = JBScrollPane(textArea).apply {
            border = JBUI.Borders.empty(2, 0, 6, 0)
            viewportBorder = JBUI.Borders.empty()
            isOpaque = true
            background = JBColor(0xFFFFFF, 0x3C3F41)
            viewport.isOpaque = true
            viewport.background = JBColor(0xFFFFFF, 0x3C3F41)
        }
        card.add(scrollPane, BorderLayout.CENTER)

        return card
    }

    // ================================================================
    // 下拉框主题自适应样式
    // ================================================================

    private fun styleDropdown(combo: JComboBox<String>) {
        combo.apply {
            font = font.deriveFont(12f)
            foreground = JBColor(0x333333, 0xD4D4D4)
            background = JBColor(0xF0F0F0, 0x2B2B2B)
            isOpaque = true
            setBorder(BorderFactory.createCompoundBorder(
                object : AbstractBorder() {
                    override fun getBorderInsets(c: java.awt.Component) = JBUI.insets(6, 10, 6, 10)
                    override fun paintBorder(c: java.awt.Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
                        val g2 = g.create() as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = JBColor(0xC0C0C0, 0x505254)
                        g2.drawRoundRect(x, y, w - 1, h - 1, 4, 4)
                        g2.dispose()
                    }
                },
                JBUI.Borders.empty(0)
            ))
            setRenderer(createThemeAwareRenderer())
        }
    }

    private fun createThemeAwareRenderer() = object : ListCellRenderer<String> {
        private val label = JLabel().apply {
            isOpaque = true
            border = JBUI.Borders.empty(4, 10)
            font = font.deriveFont(12f)
        }

        override fun getListCellRendererComponent(
            list: JList<out String>?,
            value: String?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): JComponent {
            label.text = value ?: ""
            label.background = when {
                isSelected -> Color(0x4B6EAF)
                else -> JBColor(0xF0F0F0, 0x3C3F41)
            }
            label.foreground = when {
                isSelected -> Color.WHITE
                else -> JBColor(0x333333, 0xD4D4D4)
            }
            return label
        }
    }

    // ================================================================
    // 主题自适应文本区域（带占位文字）
    // ================================================================

    private class ThemeAwareTextArea(private val placeholder: String) : JTextArea() {
        init {
            lineWrap = true
            wrapStyleWord = true
            font = font.deriveFont(13f)
            foreground = JBColor(0x333333, 0xD4D4D4)
            background = JBColor(0xFFFFFF, 0x3C3F41)
            caretColor = JBColor(0x333333, 0xD4D4D4)
            margin = JBUI.insets(10)
            selectionColor = Color(0x4B6EAF)
            selectedTextColor = Color.WHITE
            tabSize = 2
            isOpaque = true
        }

        override fun paintComponent(g: Graphics) {
            // 绘制圆角背景
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = background
            g2.fillRoundRect(0, 0, width, height, 4, 4)
            g2.dispose()

            super.paintComponent(g)

            // 占位文字
            if (text.isEmpty() && !isFocusOwner) {
                val g3 = g.create() as Graphics2D
                g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g3.color = JBColor(0x999999, 0x6C6C6C)
                g3.font = font.deriveFont(Font.PLAIN, 13f)
                val fm = g3.fontMetrics
                val x = margin.left
                val y = margin.top + fm.ascent
                g3.drawString(placeholder, x, y)
                g3.dispose()
            }
        }
    }

    // ================================================================
    // ⇄ 互换箭头按钮（圆形）
    // ================================================================

    private fun createSwapButton(): JComponent {
        val btn = object : JButton() {
            private var hovered = false

            init {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = I18n.tr("translate.swap")

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseEntered(e: java.awt.event.MouseEvent) { hovered = true; repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent) { hovered = false; repaint() }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val cx = width / 2
                val cy = height / 2
                val r = minOf(width, height) / 2 - 1

                g2.color = if (hovered) JBColor(0xD0D0D0, 0x5A5A5D) else JBColor(0xE0E0E0, 0x4E4E50)
                g2.fillOval(cx - r, cy - r, r * 2, r * 2)

                g2.font = g2.font.deriveFont(Font.PLAIN, 16f)
                g2.color = JBColor(0x666666, 0xAAAAAA)
                val fm = g2.fontMetrics
                val txt = "\u21C4"
                val tx = cx - fm.stringWidth(txt) / 2
                val ty = cy + (fm.ascent - fm.descent) / 2
                g2.drawString(txt, tx, ty)
                g2.dispose()
            }
        }.apply {
            preferredSize = Dimension(36, 36)
            minimumSize = Dimension(36, 36)
            maximumSize = Dimension(36, 36)
        }

        btn.addActionListener {
            val tLang = leftLangCombo.selectedIndex
            leftLangCombo.selectedIndex = rightLangCombo.selectedIndex
            rightLangCombo.selectedIndex = tLang

            val tText = leftTextArea.text
            leftTextArea.text = rightTextArea.text
            rightTextArea.text = tText
        }

        return btn
    }

    // ================================================================
    // 翻译 — 调用 Agnes API
    // ================================================================

    private fun performTranslation() {
        val sourceText = leftTextArea.text
        if (sourceText.isBlank()) {
            rightTextArea.text = ""
            return
        }

        val sourceLang = leftLangCombo.selectedItem?.toString() ?: "中文"
        val targetLang = rightLangCombo.selectedItem?.toString() ?: "English"

        rightTextArea.text = I18n.tr("translate.progress")
        translateBtn.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = translateWithAgnes(sourceText, sourceLang, targetLang)
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { rightTextArea.text = it },
                    onFailure = {
                        rightTextArea.text = I18n.tr("translate.error", it.message)
                        Messages.showErrorDialog(window, I18n.tr("translate.error", it.message), I18n.tr("translate.error"))
                    }
                )
                translateBtn.isEnabled = true
            }
        }
    }

    /** 使用 Agnes API 执行翻译 */
    private fun translateWithAgnes(
        text: String,
        sourceLang: String,
        targetLang: String
    ): Result<String> {
        val settings = DeepSeekSettings.instance
        if (settings.agnesApiKey.isBlank()) {
            return Result.failure(Exception(I18n.tr("translate.no.key")))
        }

        val messages = listOf(
            mapOf("role" to "system", "content" to DOMAIN_RESTRICTION_PROMPT),
            mapOf("role" to "user", "content" to "请将以下${sourceLang}文本翻译为${targetLang}，只返回翻译结果，不要添加任何解释：\n\n$text")
        )
        val bodyMap = mapOf(
            "model" to settings.agnesModel.ifBlank { "agnes-2.0-flash" },
            "messages" to messages,
            "max_tokens" to 4096,
            "stream" to false
        )

        val gson = Gson()
        val jsonBody = gson.toJson(bodyMap)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("${settings.agnesBaseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${settings.agnesApiKey}")
            .header("Content-Type", "application/json")
            .post(jsonBody)
            .build()

        return try {
            val response = HttpClientProvider.translateClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return Result.failure(Exception("API error ${response.code}: $responseBody"))
            }
            val json = gson.fromJson(responseBody, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val choices = json["choices"] as? List<Map<String, Any>> ?: emptyList()
            val content = choices.firstOrNull()
                ?.let { it["message"] as? Map<String, Any> }
                ?.let { it["content"] as? String }
                ?.trim()
            if (content.isNullOrBlank()) {
                Result.failure(Exception("翻译返回了空内容"))
            } else {
                Result.success(content)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ================================================================
    // 仅保留自定义「翻译」按钮，移除原生 OK/Cancel
    // ================================================================

    override fun createActions(): Array<javax.swing.Action> = emptyArray()

    override fun doOKAction() {
        super.doOKAction()
    }

    companion object {
        private lateinit var translateBtn: JButton
    }
}
