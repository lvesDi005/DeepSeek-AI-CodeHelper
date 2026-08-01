package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.ActionListener
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 代码变更预览工具类。
 *
 * 流程：
 * 1. 使用 side-by-side 对比面板展示原始代码与生成代码
 * 2. 用户审查后决定是否「应用更改」
 */
object CodeDiffUtil {

    /**
     * 单个文件的 diff 变更信息。
     */
    data class FileDiffItem(
        val filePath: String,
        val action: String, // "write" or "delete"
        val originalContent: String,
        val newContent: String
    )

    /**
     * 展示 diff 对比并询问用户是否应用更改。
     *
     * @param project 当前项目
     * @param originalCode 原始代码
     * @param newCode 新生成的代码
     * @param onApply 用户确认后执行的回调（在 EDT 中调用）
     */
    @JvmStatic
    fun showDiffAndApply(
        project: Project,
        originalCode: String,
        newCode: String,
        onApply: () -> Unit
    ) {
        val dialog = DiffPreviewDialog(project, originalCode, newCode, null)
        if (dialog.showAndGet()) {
            onApply()
        }
    }

    /**
     * 展示 diff 对比并返回用户是否确认应用更改。
     * 代码无变化时自动返回 true（无需确认）。
     *
     * @param project 当前项目
     * @param originalCode 原始代码
     * @param newCode 新生成的代码
     * @param filePath 可选的文件路径，用于对话框标题
     * @return true 表示用户确认应用，false 表示跳过
     */
    @JvmStatic
    fun showDiffAndConfirm(
        project: Project,
        originalCode: String,
        newCode: String,
        filePath: String? = null
    ): Boolean {
        if (originalCode == newCode) {
            return true // 无变化，自动确认
        }
        val dialog = DiffPreviewDialog(project, originalCode, newCode, filePath)
        return dialog.showAndGet()
    }

    /**
     * 展示 diff 对比并询问用户是否应用更改。
     * 代码无变化时自动跳过。
     */
    @JvmStatic
    fun showDiffIfChanged(
        project: Project,
        originalCode: String,
        newCode: String,
        onApply: () -> Unit
    ): Boolean {
        if (originalCode == newCode) {
            Messages.showInfoMessage(project, I18n.tr("diff.no.change"), I18n.tr("diff.no.change.title"))
            return false
        }
        showDiffAndApply(project, originalCode, newCode, onApply)
        return true
    }

    /**
     * 批量展示所有文件的变更对比，一个汇总列表，每个文件可展开查看 diff。
     * 用户确认后统一执行所有变更。
     *
     * @param project 当前项目
     * @param items 所有文件的变更信息列表
     * @return true 表示用户确认执行全部变更，false 表示取消
     */
    @JvmStatic
    fun showBatchDiffAndConfirm(
        project: Project,
        items: List<FileDiffItem>
    ): Boolean {
        // 过滤出有实际变化的文件
        val changedItems = items.filter { it.originalContent != it.newContent }
        if (changedItems.isEmpty()) {
            return true // 无变化，自动确认
        }
        val dialog = BatchDiffDialog(project, changedItems)
        return dialog.showAndGet()
    }

    // ================================================================
    // 单文件 diff 预览对话框
    // ================================================================

    private class DiffPreviewDialog(
        project: Project,
        private val originalCode: String,
        private val newCode: String,
        filePath: String? = null
    ) : DialogWrapper(project, true) {

        init {
            title = if (filePath != null) {
                I18n.tr("diff.preview.title") + " - " + filePath
            } else {
                I18n.tr("diff.preview.title")
            }
            isResizable = true
            setOKButtonText(I18n.tr("diff.apply"))
            setCancelButtonText(I18n.tr("chat.cancel"))
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(8)
            panel.preferredSize = Dimension(800, 500)

            // ── 标题行 ──
            val headerPanel = JPanel(GridLayout(1, 2))
            headerPanel.add(createHeaderLabel(I18n.tr("diff.original"), JBColor(0x666666, 0xBBBBBB)))
            headerPanel.add(createHeaderLabel(I18n.tr("diff.generated"), JBColor(0x4477AA, 0x6699CC)))
            panel.add(headerPanel, BorderLayout.NORTH)

            // ── 左右对比区域 ──
            val diffPanel = JPanel(GridLayout(1, 2, 8, 0))
            diffPanel.add(createCodePanel(originalCode, JBColor(0xF5F5F5, 0x1E1E22)))
            diffPanel.add(createCodePanel(newCode, JBColor(0xF0F7F0, 0x1E2A1E)))
            panel.add(diffPanel, BorderLayout.CENTER)

            return panel
        }

        private fun createHeaderLabel(text: String, color: Color): JLabel {
            return JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = color
                border = JBUI.Borders.empty(4, 8, 8, 8)
                horizontalAlignment = JLabel.CENTER
            }
        }

        private fun createCodePanel(code: String, bg: Color): JComponent {
            val textArea = JBTextArea(code).apply {
                isEditable = false
                lineWrap = false
                font = JBUI.Fonts.create("Monospaced", 12)
                background = bg
                foreground = JBColor(0x000000, 0xD4D4D4)
                caretColor = foreground
                margin = JBUI.insets(10)
                border = JBUI.Borders.customLine(JBColor(0xCCCCCC, 0x444444), 1)
                selectedTextColor = JBColor.WHITE
                selectionColor = JBColor(0x3399FF, 0x2D5B9E)
            }
            return JBScrollPane(textArea).apply {
                border = JBUI.Borders.empty()
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            }
        }
    }

    // ================================================================
    // 批量 diff 预览对话框 — 汇总列表 + 可展开查看每个文件的 diff
    // ================================================================

    private class BatchDiffDialog(
        project: Project,
        private val items: List<FileDiffItem>
    ) : DialogWrapper(project, true) {

        init {
            title = I18n.tr("diff.batch.title", items.size)
            isResizable = true
            setOKButtonText(I18n.tr("diff.batch.load"))
            setCancelButtonText(I18n.tr("chat.cancel"))
            init()
        }

        override fun createCenterPanel(): JComponent {
            val outerPanel = JPanel(BorderLayout())
            outerPanel.border = JBUI.Borders.empty(8)
            outerPanel.preferredSize = Dimension(880, 550)

            // ── 文件列表卡片区域（可滚动） ──
            val cardPanel = JPanel()
            cardPanel.layout = BoxLayout(cardPanel, BoxLayout.Y_AXIS)
            cardPanel.border = JBUI.Borders.empty(4)

            for ((index, item) in items.withIndex()) {
                if (index > 0) {
                    cardPanel.add(Box.createVerticalStrut(4))
                }
                cardPanel.add(FileDiffCard(item))
            }

            val scrollPane = JBScrollPane(cardPanel).apply {
                border = JBUI.Borders.customLine(JBColor(0xCCCCCC, 0x444444), 1)
                verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }
            outerPanel.add(scrollPane, BorderLayout.CENTER)

            return outerPanel
        }

        /**
         * 单个文件的变更卡片：标题行 + 可折叠的 diff 对比面板。
         */
        private inner class FileDiffCard(
            private val item: FileDiffItem
        ) : JPanel(BorderLayout()) {
            private var diffVisible = false
            private val diffPanel: JPanel
            private val expandBtn: JButton

            init {
                border = JBUI.Borders.customLine(JBColor(0xBBBBBB, 0x555555), 1)
                val bg = JBColor(0xFAFAFA, 0x2B2B2B)
                background = bg

                // ── 标题行 ──
                val headerPanel = JPanel(BorderLayout()).apply {
                    background = bg
                    border = JBUI.Borders.empty(4, 8, 4, 8)
                }

                // 文件路径 + 操作标识
                val actionLabel = if (item.action == "write" && item.originalContent.isEmpty()) {
                    "  🆕"
                } else if (item.action == "delete") {
                    "  🗑"
                } else {
                    "  📝"
                }

                val nameLabel = JLabel(item.filePath + actionLabel).apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                    foreground = JBColor(0x000000, 0xD4D4D4)
                }
                headerPanel.add(nameLabel, BorderLayout.WEST)

                // 展开/折叠按钮
                expandBtn = JButton(I18n.tr("diff.batch.expand")).apply {
                    font = font.deriveFont(11f)
                    border = JBUI.Borders.empty(2, 12)
                    isFocusPainted = false
                    addActionListener {
                        toggleDiff()
                    }
                }
                headerPanel.add(expandBtn, BorderLayout.EAST)

                add(headerPanel, BorderLayout.NORTH)

                // ── diff 对比面板（初始隐藏） ──
                diffPanel = createDiffContentPanel(item.originalContent, item.newContent)
                diffPanel.isVisible = false
                add(diffPanel, BorderLayout.CENTER)
            }

            private fun toggleDiff() {
                diffVisible = !diffVisible
                diffPanel.isVisible = diffVisible
                expandBtn.text = if (diffVisible) I18n.tr("diff.batch.collapse") else I18n.tr("diff.batch.expand")
                // 通知父容器刷新布局
                revalidate()
                // 找到最外层的 JScrollPane 并滚动到可见区域
                if (diffVisible) {
                    scrollRectToVisible(bounds)
                }
            }

            private fun createDiffContentPanel(originalCode: String, newCode: String): JPanel {
                val panel = JPanel(BorderLayout())
                panel.border = JBUI.Borders.empty(4, 8, 8, 8)

                // 标题行
                val headerPanel = JPanel(GridLayout(1, 2))
                headerPanel.add(createBatchHeaderLabel(I18n.tr("diff.original"), JBColor(0x666666, 0xBBBBBB)))
                headerPanel.add(createBatchHeaderLabel(I18n.tr("diff.generated"), JBColor(0x4477AA, 0x6699CC)))
                panel.add(headerPanel, BorderLayout.NORTH)

                // 左右对比
                val diffContent = JPanel(GridLayout(1, 2, 8, 0))
                diffContent.add(createCodePanel(originalCode, JBColor(0xF5F5F5, 0x1E1E22)))
                diffContent.add(createCodePanel(newCode, JBColor(0xF0F7F0, 0x1E2A1E)))
                panel.add(diffContent, BorderLayout.CENTER)

                return panel
            }

            private fun createBatchHeaderLabel(text: String, color: Color): JLabel {
                return JLabel(text).apply {
                    font = font.deriveFont(Font.BOLD, 11f)
                    foreground = color
                    border = JBUI.Borders.empty(2, 8, 4, 8)
                    horizontalAlignment = SwingConstants.CENTER
                }
            }

            private fun createCodePanel(code: String, bg: Color): JComponent {
                val textArea = JBTextArea(code).apply {
                    isEditable = false
                    lineWrap = false
                    font = JBUI.Fonts.create("Monospaced", 11)
                    background = bg
                    foreground = JBColor(0x000000, 0xD4D4D4)
                    caretColor = foreground
                    margin = JBUI.insets(6)
                    border = JBUI.Borders.customLine(JBColor(0xCCCCCC, 0x444444), 1)
                    selectedTextColor = JBColor.WHITE
                    selectionColor = JBColor(0x3399FF, 0x2D5B9E)
                }
                return JBScrollPane(textArea).apply {
                    border = JBUI.Borders.empty()
                    preferredSize = Dimension(400, 150)
                    verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                    horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                }
            }
        }
    }
}
