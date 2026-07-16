package com.deepseek.plugin.completion

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import org.jetbrains.annotations.Nls

/**
 * 状态栏 Widget 工厂 — 占位实现。
 *
 * 当前 IntelliJ SDK 版本（241+）的 StatusBarWidget API 已重构，
 * 需要适配后才能正常工作。暂时保留占位实现，
 * 实际编译时通过 build.gradle 条件编译跳过。
 */
class CompletionStatusBarWidgetFactory : com.intellij.openapi.wm.StatusBarWidgetFactory {

    override fun getId(): String = "DeepSeekCompletionStatus"

    override fun getDisplayName(): @Nls String = "DeepSeek AI Completion Status"

    override fun isAvailable(project: Project): Boolean = false  // 暂时禁用

    override fun createWidget(project: Project): StatusBarWidget {
        throw UnsupportedOperationException("StatusBarWidget not yet adapted for this SDK version")
    }

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = false
}
