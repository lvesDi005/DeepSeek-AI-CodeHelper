package com.deepseek.plugin.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * 模型目录弹窗 — 展示各品牌商支持的模型列表。
 * 独立弹窗，不阻塞用户填写其他设置。
 */
class ModelCatalogDialog : DialogWrapper(true) {

    private data class ModelEntry(
        val provider: String,
        val providerDisplay: String,
        val modelId: String,
        val notes: String
    )

    private val models = listOf(
        // DeepSeek
        ModelEntry("deepseek", "DeepSeek", "deepseek-v4-flash", "高速编码，支持 FIM 代码补全（默认）"),
        ModelEntry("deepseek", "DeepSeek", "deepseek-v4-pro", "深度推理/规划，适合 Planner 角色"),
        // Agnes
        ModelEntry("agnes", "Agnes 2.0 Flash", "agnes-2.0-flash", "轻量分析/审查，适合意图确认和 Reviewer"),
        // NVIDIA
        ModelEntry("nvidia", "NVIDIA NIM", "z-ai/glm-5.2", "GLM-5.2 中文理解强，适合需求分析"),
        ModelEntry("nvidia", "NVIDIA NIM", "minimaxai/minimax-m3", "MiniMax M3 推理均衡，适合代码审查"),
        ModelEntry("nvidia", "NVIDIA NIM", "stepfun-ai/step-3.7-flash", "阶跃星辰 Flash，速度快成本低"),
        // StepFun (image parsing)
        ModelEntry("stepfun", "StepFun", "step-1o-turbo-vision", "多模态视觉解析（图片解析专用）"),
        // OpenRouter
        ModelEntry("openrouter", "OpenRouter", "poolside/laguna-xs-2.1:free", "Poolside Laguna XS 免费版，适合代码生成")
    )

    init {
        title = "Model Catalog / 模型目录"
        isModal = false  // 非模态，不阻塞用户填写其他信息
        init()
    }

    override fun createCenterPanel(): JComponent {
        val columnNames = arrayOf("Provider / 供应商", "Model ID / 模型标识", "Notes / 说明")
        val data = models.map { arrayOf(it.providerDisplay, it.modelId, it.notes) }.toTypedArray()

        val tableModel = object : DefaultTableModel(data, columnNames) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        val table = JTable(tableModel).apply {
            setShowGrid(true)
            gridColor = JBColor(0xD0D0D0, 0x4A4A4A)
            rowHeight = 28
            font = JBUI.Fonts.label()
            getColumnModel().getColumn(0).preferredWidth = 140
            getColumnModel().getColumn(1).preferredWidth = 220
            getColumnModel().getColumn(2).preferredWidth = 180
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS

            // 表头渲染
            tableHeader.font = JBUI.Fonts.label().deriveFont(java.awt.Font.BOLD)
            tableHeader.background = JBColor(0xF0F0F0, 0x3C3C3C)

            // 交替行颜色
            val alternateColor = JBColor(0xF5F5F5, 0x2A2A2A)
            setDefaultRenderer(String::class.java, object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(
                    table: JTable, value: Any?,
                    isSelected: Boolean, hasFocus: Boolean,
                    row: Int, column: Int
                ): java.awt.Component {
                    val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                    if (!isSelected) {
                        comp.background = if (row % 2 == 0) table.background else alternateColor
                    }
                    border = JBUI.Borders.empty(2, 8, 2, 8)
                    return comp
                }
            })
        }

        val scrollPane = JBScrollPane(table).apply {
            preferredSize = Dimension(520, 300)
            border = JBUI.Borders.empty(8)
        }

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 4, 4, 4)
            add(scrollPane)
        }
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction.apply { putValue(Action.NAME, "Close / 关闭") })
    }
}
