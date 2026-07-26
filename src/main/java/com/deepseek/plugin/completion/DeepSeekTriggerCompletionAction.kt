package com.deepseek.plugin.completion

import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.api.TriggerMode
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware

/**
 * 手动触发 AI 补全（Alt+P / 右键菜单）。
 *
 * 与自动触发（CompletionContributor）不同的是：
 * - 跳过缓存，总是调用 API
 * - 使用更高的 temperature（0.2 默认）和更多 max_tokens（512 默认）
 * - 生成更长、更多样的补全结果
 * - 以 Ghost Text 形式展示，按 Tab 接受
 *
 * 借鉴 deepseek-copilot 的 DeepSeekTriggerCompletionAction。
 */
class DeepSeekTriggerCompletionAction : AnAction(), DumbAware {

    companion object {
        private val LOG = Logger.getInstance(DeepSeekTriggerCompletionAction::class.java)
        private const val ACTION_ID = "DeepSeekAI.TriggerCompletion"
    }

    private val client = DeepSeekApiClient()

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val settings = DeepSeekSettings.instance

        if (!settings.completionEnabled || settings.apiKey.isBlank()) {
            return
        }

        // 位置预处理
        val document = editor.document
        val caretOffset = editor.caretModel.offset
        val cursorLine = document.getLineNumber(caretOffset)

        // 收集前缀（光标前最多 30 行）
        val startLine = maxOf(0, cursorLine - settings.maxContextLines)
        val startOffset = document.getLineStartOffset(startLine)
        val prefix = document.getText(com.intellij.openapi.util.TextRange(startOffset, caretOffset))

        // 收集后缀（光标后最多 20 行）
        val endLine = minOf(document.lineCount - 1, cursorLine + 20)
        val endOffset = if (endLine > cursorLine) document.getLineEndOffset(endLine) else caretOffset
        val suffix = document.getText(com.intellij.openapi.util.TextRange(caretOffset, endOffset))

        // 获取语言
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val language = when {
            !file.fileType.isBinary -> file.fileType.name
            else -> inferLanguage(file.name)
        }

        // 收集文件上下文（包声明 + imports）
        val fileContext = collectFileContext(document, cursorLine, 30)

        // 状态更新
        val statusService = CompletionStatusService.instance
        statusService.onGenerating()

        // 清理已有的 Ghost Text
        GhostTextManager.dismissGhostText(editor)

        LOG.info("Manual trigger | lang=$language | prefixLen=${prefix.length} | suffixLen=${suffix.length}")

        // 异步调用 FIM API（MANUAL 模式）
        client.completionFim(
            prefix = prefix,
            suffix = suffix,
            language = language,
            fileContext = fileContext,
            mode = TriggerMode.MANUAL
        ) { suggestionRaw ->
            if (suggestionRaw.isNullOrBlank()) {
                statusService.onIdle()
                return@completionFim
            }

            val lastLine = prefix.lines().lastOrNull() ?: ""
            val suggestion = CompletionPostProcessor.process(suggestionRaw, lastLine, suffix)

            if (suggestion.isBlank()) {
                statusService.onIdle()
                return@completionFim
            }

            // 回到 EDT 显示 Ghost Text
            ApplicationManager.getApplication().invokeLater {
                if (!editor.isDisposed) {
                    GhostTextManager.showGhostText(editor, suggestion, caretOffset)
                    LOG.info("Manual completion shown | length=${suggestion.length}")
                    statusService.onReady()
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val settings = DeepSeekSettings.instance
        e.presentation.isEnabledAndVisible =
            editor != null &&
            e.project != null &&
            settings.completionEnabled &&
            settings.apiKey.isNotBlank()
    }

    private fun inferLanguage(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "JAVA"
            "kt", "kts" -> "Kotlin"
            "py" -> "Python"
            "js" -> "JavaScript"
            "ts" -> "TypeScript"
            "go" -> "Go"
            "rs" -> "Rust"
            "cpp", "cc", "cxx" -> "C++"
            "c" -> "C"
            "cs" -> "C#"
            "rb" -> "Ruby"
            "php" -> "PHP"
            "swift" -> "Swift"
            "scala" -> "Scala"
            "xml" -> "XML"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "sql" -> "SQL"
            "sh", "bash" -> "Shell"
            "" -> "text"
            else -> ext.uppercase()
        }
    }

    private fun collectFileContext(document: com.intellij.openapi.editor.Document, cursorLine: Int, maxLines: Int): String {
        val sb = StringBuilder()
        val headerEnd = minOf(20, cursorLine)
        for (i in 0 until headerEnd) {
            val lineStart = document.getLineStartOffset(i)
            val lineEnd = document.getLineEndOffset(i)
            val line = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
            if (line.isNotBlank()) sb.appendLine(line)
        }
        if (cursorLine > 20) sb.appendLine("// ...")
        return sb.toString()
    }
}
