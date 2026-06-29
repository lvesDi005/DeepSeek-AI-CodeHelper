package com.deepseek.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 代码变更预览工具类。
 *
 * 流程：
 * 1. 使用 side-by-side 对比面板展示原始代码与生成代码
 * 2. 用户审查后决定是否「应用更改」
 */
object CodeDiffUtil {

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
        val dialog = DiffPreviewDialog(project, originalCode, newCode)
        if (dialog.showAndGet()) {
            onApply()
        }
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
            Messages.showInfoMessage(project, "生成的代码与原始代码相同，无需变更。", "无变更")
            return false
        }
        showDiffAndApply(project, originalCode, newCode, onApply)
        return true
    }

    // ================================================================
    // 自定义 diff 预览对话框
    // ================================================================

    private class DiffPreviewDialog(
        project: Project,
        private val originalCode: String,
        private val newCode: String
    ) : DialogWrapper(project, true) {

        init {
            title = "预览代码变更"
            isResizable = true
            setOKButtonText("应用更改")
            setCancelButtonText("取消")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(8)
            panel.preferredSize = Dimension(800, 500)

            // ── 标题行 ──
            val headerPanel = JPanel(GridLayout(1, 2))
            headerPanel.add(createHeaderLabel("原始代码", JBColor(0x666666, 0x999999)))
            headerPanel.add(createHeaderLabel("生成的代码", JBColor(0x4477AA, 0x6699CC)))
            panel.add(headerPanel, BorderLayout.NORTH)

            // ── 左右对比区域 ──
            val diffPanel = JPanel(GridLayout(1, 2, 8, 0))
            diffPanel.add(createCodePanel(originalCode, JBColor(0xF5F5F5, 0x1E1E22)))
            diffPanel.add(createCodePanel(newCode, JBColor(0xF0F7F0, 0x1E2A1E)))
            panel.add(diffPanel, BorderLayout.CENTER)

            return panel
        }

        private fun createHeaderLabel(text: String, color: JBColor): JLabel {
            return JLabel(text).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = color
                border = JBUI.Borders.empty(4, 8, 8, 8)
                horizontalAlignment = JLabel.CENTER
            }
        }

        private fun createCodePanel(code: String, bg: JBColor): JComponent {
            val textArea = JBTextArea(code).apply {
                isEditable = false
                lineWrap = false
                font = JBUI.Fonts.create("Monospaced", 12)
                background = bg
                foreground = JBColor(0x333333, 0xD4D4D4)
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
}
