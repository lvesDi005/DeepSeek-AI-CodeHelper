package com.deepseek.plugin.completion

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.Nls
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Consumer
import javax.swing.JLabel

/**
 * 状态栏 Widget — 显示 AI 代码补全当前状态（空闲/生成中/就绪/出错）。
 *
 * 点击可切换补全启用/禁用。
 */
class CompletionStatusBarWidgetFactory : com.intellij.openapi.wm.StatusBarWidgetFactory {

    override fun getId(): String = "DeepSeekCompletionStatus"

    override fun getDisplayName(): @Nls String = "DeepSeek AI Completion Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return CompletionStatusWidget()
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * 状态栏组件 — JLabel 直接实现 StatusBarWidget，显示当前 AI 补全状态。
 */
class CompletionStatusWidget : JLabel(), StatusBarWidget, StatusBarWidget.WidgetPresentation {

    private var lastState: CompletionStatusService.State = CompletionStatusService.State.IDLE

    init {
        font = font.deriveFont(Font.PLAIN, JBUI.Fonts.label().size.toFloat())
        text = ""
        isOpaque = false
        border = JBUI.Borders.empty(0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val settings = com.deepseek.plugin.settings.DeepSeekSettings.instance
                settings.completionEnabled = !settings.completionEnabled
                refreshText()
            }
        })

        CompletionStatusService.instance.addListener { newState ->
            lastState = newState
            refreshText()
        }
        refreshText()
    }

    private fun refreshText() {
        val settings = com.deepseek.plugin.settings.DeepSeekSettings.instance
        if (!settings.completionEnabled) {
            text = " AI OFF "
            foreground = java.awt.Color(0x999999)
        } else {
            text = when (lastState) {
                CompletionStatusService.State.IDLE -> ""
                CompletionStatusService.State.GENERATING -> " AI\u22EF "
                CompletionStatusService.State.READY -> " AI\u2713 "
                CompletionStatusService.State.ERROR -> " AI\u2717 "
            }
            foreground = when (lastState) {
                CompletionStatusService.State.IDLE -> null
                CompletionStatusService.State.GENERATING -> java.awt.Color(0x2196F3)
                CompletionStatusService.State.READY -> java.awt.Color(0x4CAF50)
                CompletionStatusService.State.ERROR -> java.awt.Color(0xFF5722)
            }
        }
        revalidate()
        repaint()
    }

    // StatusBarWidget

    override fun ID(): String = "DeepSeekCompletionStatus"

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {}

    // WidgetPresentation — return self so the framework uses this JLabel directly

    override fun getPresentation(): StatusBarWidget.WidgetPresentation? = this

    override fun getClickConsumer() = null

    override fun getTooltipText(): String? = null
}
