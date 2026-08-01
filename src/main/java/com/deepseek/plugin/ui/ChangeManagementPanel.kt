package com.deepseek.plugin.ui

import com.deepseek.plugin.i18n.I18n
import com.deepseek.plugin.store.ChangeManagementStore
import com.deepseek.plugin.store.ChangeRecord
import com.deepseek.plugin.store.FileChangeInfo
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.border.CompoundBorder

/**
 * 变更管理面板 — 全屏覆盖的变更查看与回滚界面。
 *
 * 展示所有 Agent 模式产生的变更记录，每条记录可展开查看具体文件，
 * 并提供「回滚」和「查看变更」两个操作按钮。
 *
 * @param project 当前 IntelliJ 项目
 * @param onClose 关闭面板返回聊天主界面时回调
 */
class ChangeManagementPanel(
    private val project: Project,
    private val onClose: () -> Unit
) : JPanel(BorderLayout()) {

    private val recordListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val changeStore = project.getService(ChangeManagementStore::class.java)

    init {
        background = JBColor(Color(0xF0F0F0), Color(0x3C3F41))
        border = JBUI.Borders.empty()

        // ── 顶部标题栏 ──
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = CompoundBorder(
                JBUI.Borders.empty(8, 12, 8, 12),
                JBUI.Borders.customLineBottom(JBColor(Color(0xD0D0D0), Color(0x555555)))
            )
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 80)
        }

        val titleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 30)
        }

        val titleLabel = JLabel(I18n.tr("change.title")).apply {
            font = JBUI.Fonts.label().asBold()
            foreground = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))
        }
        titleRow.add(titleLabel, BorderLayout.WEST)

        val closeButton = createToolbarButton(
            icon = AllIcons.Actions.Close,
            tooltip = I18n.tr("change.back"),
            tooltipKey = "change.back",
            onClick = { onClose() }
        )
        titleRow.add(closeButton, BorderLayout.EAST)
        headerPanel.add(titleRow)

        add(headerPanel, BorderLayout.NORTH)

        // ── 记录列表（可滚动） ──
        val scrollPane = JBScrollPane(recordListPanel).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        refreshRecords()
    }

    /**
     * 刷新变更记录列表（从 ChangeManagementStore 重新读取）。
     */
    fun refreshRecords() {
        recordListPanel.removeAll()
        val records = changeStore.records
        if (records.isEmpty()) {
            val emptyLabel = JLabel(I18n.tr("change.empty")).apply {
                font = JBUI.Fonts.label()
                foreground = JBColor(Color(0x999999), Color(0x777777))
                alignmentX = Component.CENTER_ALIGNMENT
                horizontalAlignment = SwingConstants.CENTER
            }
            recordListPanel.add(Box.createVerticalGlue())
            recordListPanel.add(emptyLabel)
            recordListPanel.add(Box.createVerticalGlue())
        } else {
            recordListPanel.add(Box.createVerticalStrut(8))
            for (record in records) {
                recordListPanel.add(ChangeRecordCard(record))
                recordListPanel.add(Box.createVerticalStrut(8))
            }
        }
        recordListPanel.revalidate()
        recordListPanel.repaint()
    }

    // ════════════════════════════════════════════════════════════════
    //  内部类：单条变更记录卡片
    // ════════════════════════════════════════════════════════════════

    /**
     * 一条变更记录的卡片 UI。
     *
     * 默认只显示标题行（标题 + 时间），点击可展开/收起文件列表。
     * 每个文件项右下角有「回滚」和「查看变更」按钮。
     */
    inner class ChangeRecordCard(private val record: ChangeRecord) : JPanel() {

        private var expanded = false
        private val expandIcon = JLabel(I18n.tr("change.collapse")).apply {
            font = JBUI.Fonts.label().asBold()
            foreground = JBColor(Color(0x888888), Color(0x999999))
        }
        private val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 0, 12)

            // ── 标题行（可点击展开/收起） ──
            val headerRow = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = JBColor(Color(0xFFFFFF), Color(0x4A4A4A))
                border = CompoundBorder(
                    JBUI.Borders.customLine(JBColor(Color(0xE0E0E0), Color(0x555555))),
                    JBUI.Borders.empty(8, 10, 8, 10)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        expanded = !expanded
                        refreshExpandState()
                    }
                })
            }

            // 展开/收起箭头指示器
            headerRow.add(expandIcon, BorderLayout.WEST)

            val titleText = "  ${record.title}  ${record.formattedTime()}"
            val titleLabel = JLabel(titleText).apply {
                font = JBUI.Fonts.label()
                foreground = JBColor(Color(0x333333), Color(0xCCCCCC))
            }
            headerRow.add(titleLabel, BorderLayout.CENTER)

            // 右侧操作区：回滚本条 + 删除
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
            }

            val rollbackThisBtn = createToolbarButton(
                icon = AllIcons.Actions.Rollback,
                tooltip = I18n.tr("change.rollback"),
                tooltipKey = "change.rollback",
                size = 22,
                onClick = { rollbackThisRecord() }
            )
            rightPanel.add(rollbackThisBtn)

            val deleteThisBtn = createToolbarButton(
                icon = AllIcons.Actions.GC,
                tooltip = I18n.tr("change.delete"),
                tooltipKey = "change.delete",
                size = 22,
                onClick = { deleteThisRecord() }
            )
            rightPanel.add(deleteThisBtn)
            headerRow.add(rightPanel, BorderLayout.EAST)

            add(headerRow)

            // ── 文件列表区（默认隐藏） ──
            contentPanel.isVisible = false
            contentPanel.add(Box.createVerticalStrut(4))
            for (changeInfo in record.changes) {
                contentPanel.add(FileChangeRow(changeInfo))
                contentPanel.add(Box.createVerticalStrut(4))
            }
            add(contentPanel)
        }

        private fun refreshExpandState() {
            expandIcon.text = if (expanded) I18n.tr("change.expand") else I18n.tr("change.collapse")
            contentPanel.isVisible = expanded
            revalidate()
            repaint()
            // 通知父级刷新布局
            recordListPanel.revalidate()
            recordListPanel.repaint()
        }

        override fun getMaximumSize(): Dimension {
            val pref = preferredSize
            return Dimension(Short.MAX_VALUE.toInt(), pref.height)
        }

        /**
         * 批量回滚本条记录下的所有文件变更。
         * 确认后逐个文件执行回滚，完成后移除记录并刷新面板。
         */
        private fun rollbackThisRecord() {
            val count = record.changes.size
            val confirmed = Messages.showYesNoDialog(
                project,
                I18n.tr("change.confirm.rollback", record.title, count),
                I18n.tr("change.rollback.dialog.title"),
                I18n.tr("change.confirm.rollback.all"),
                I18n.tr("chat.cancel"),
                Messages.getQuestionIcon()
            )
            if (confirmed != Messages.YES) return

            WriteCommandAction.runWriteCommandAction(project) {
                var successCount = 0
                var failCount = 0
                val failDetails = mutableListOf<String>()

                for (change in record.changes) {
                    val ok = changeStore.rollbackFile(change)
                    if (ok) {
                        successCount++
                    } else {
                        failCount++
                        failDetails.add(File(change.filePath).name)
                    }
                }

                // 无论成功多少，都移除这条记录
                changeStore.removeRecord(record)

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    refreshRecords()
                    val msg = when {
                        failCount == 0 -> I18n.tr("change.result.success", record.title, successCount, count)
                        successCount > 0 -> I18n.tr("change.result.partial", successCount, failCount, failDetails.joinToString(", "))
                        else -> I18n.tr("change.result.failed", failCount, count)
                    }
                    Messages.showInfoMessage(project, msg, I18n.tr("change.rollback"))
                }
            }
        }

        /**
         * 删除本条变更记录（不回滚文件，仅移除记录）。
         */
        private fun deleteThisRecord() {
            val confirmed = Messages.showYesNoDialog(
                project,
                I18n.tr("change.delete.record", record.title),
                I18n.tr("change.delete.dialog.title"),
                I18n.tr("change.delete"),
                I18n.tr("chat.cancel"),
                Messages.getQuestionIcon()
            )
            if (confirmed != Messages.YES) return

            changeStore.removeRecord(record)
            refreshRecords()
            Messages.showInfoMessage(project, I18n.tr("change.delete.record", record.title), I18n.tr("change.delete"))
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  内部类：单个文件变更行
    // ════════════════════════════════════════════════════════════════

    /**
     * 单个文件变更行。
     *
     * 布局:
     * ┌──────────────────────────────────────────────┐
     * │ <文件路径>                   [回滚] [查看变更] │
     * └──────────────────────────────────────────────┘
     */
    inner class FileChangeRow(private val change: FileChangeInfo) : JPanel(BorderLayout()) {

        init {
            isOpaque = true
            background = JBColor(Color(0xFAFAFA), Color(0x3E3E3E))
            border = JBUI.Borders.empty(6, 14, 6, 8)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), 40)

            // ── 文件路径标签 ──
            val fileName = File(change.filePath).name
            val fileIcon = if (change.isNew) I18n.tr("change.file.icon") else I18n.tr("change.file.icon.default")
            val fileLabel = JLabel("$fileIcon $fileName").apply {
                font = JBUI.Fonts.label()
                foreground = JBColor(Color(0x555555), Color(0xAAAAAA))
                toolTipText = change.filePath
            }
            add(fileLabel, BorderLayout.WEST)

            // ── 按钮区（右下角） ──
            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
            }

            // 查看变更按钮
            val viewChangesBtn = createToolbarButton(
                icon = AllIcons.Actions.Diff,
                tooltip = I18n.tr("change.view"),
                tooltipKey = "change.view",
                size = 22,
                onClick = { showDiff(change) }
            )
            buttonPanel.add(viewChangesBtn)

            // 回滚按钮
            val rollbackBtn = createToolbarButton(
                icon = AllIcons.Actions.Rollback,
                tooltip = I18n.tr("change.rollback.file"),
                tooltipKey = "change.rollback.file",
                size = 22,
                onClick = { rollbackFile(change) }
            )
            buttonPanel.add(rollbackBtn)

            add(buttonPanel, BorderLayout.EAST)
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  操作实现
    // ════════════════════════════════════════════════════════════════

    /**
     * 打开 IntelliJ Diff 窗口，对比原始内容与当前文件内容。
     */
    private fun showDiff(change: FileChangeInfo) {
        val fileName = File(change.filePath).name
        try {
            val file = File(change.filePath)
            val currentContent = if (file.exists()) file.readBytes() else ByteArray(0)

            val contentFactory = DiffContentFactory.getInstance()
            // Convert ByteArray to String for DiffContentFactory API compatibility
            val originalText = change.originalContent.toString(Charsets.UTF_8)
            val currentText = currentContent.toString(Charsets.UTF_8)

            // 获取 VirtualFile 以启用语法高亮（注释等会显示为对应语言颜色）
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(file)

            val leftContent = contentFactory.create(project, originalText, virtualFile)
            val rightContent = contentFactory.create(project, currentText, virtualFile)

            val request = SimpleDiffRequest(
                I18n.tr("change.diff.title", fileName),
                leftContent,
                rightContent,
                if (change.isNew) I18n.tr("change.diff.new.file") else I18n.tr("change.diff.backup"),
                I18n.tr("change.diff.current")
            )
            DiffManager.getInstance().showDiff(project, request)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, I18n.tr("change.diff.title", fileName) + ": ${e.message}", I18n.tr("change.diff.title", fileName))
        }
    }

    /**
     * 回滚单个文件：修改文件写回原始内容，新建文件直接删除。
     */
    private fun rollbackFile(change: FileChangeInfo) {
        val fileName = File(change.filePath).name
        val confirmMsg = if (change.isNew) {
            I18n.tr("change.confirm.rollback", fileName, 1)
        } else {
            I18n.tr("change.confirm.rollback", fileName, 1)
        }
        val confirmed = Messages.showYesNoDialog(
            project,
            confirmMsg,
            if (change.isNew) I18n.tr("change.rollback.dialog.title") else I18n.tr("change.rollback.dialog.title"),
            if (change.isNew) I18n.tr("change.delete") else I18n.tr("change.rollback"),
            I18n.tr("chat.cancel"),
            Messages.getQuestionIcon()
        )
        if (confirmed != Messages.YES) return

        WriteCommandAction.runWriteCommandAction(project) {
            val success = changeStore.rollbackFile(change)
            if (success) {
                Messages.showInfoMessage(project, I18n.tr("change.result.success", fileName, 1, 1), I18n.tr("change.rollback"))
            } else {
                Messages.showErrorDialog(project, I18n.tr("change.result.failed", 1, 1), I18n.tr("change.rollback"))
            }
        }
    }
}
