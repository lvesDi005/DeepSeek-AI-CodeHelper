package com.deepseek.plugin.completion

import com.deepseek.plugin.api.DeepSeekApiClient
import com.deepseek.plugin.settings.DeepSeekSettings
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * 通用代码补全入口（所有语言）。
 *
 * 注册 [DeepSeekCompletionProvider] —— AI 驱动的 FIM 补全。
 *
 * Java 专属的静态分析前置过滤见 [JavaCompletionContributor]，
 * 仅在 IntelliJ IDEA 等包含 Java 插件的 IDE 中生效。
 */
class DeepSeekCompletionContributor : CompletionContributor() {

    companion object {
        private val LOG = Logger.getInstance(DeepSeekCompletionContributor::class.java)
    }

    init {
        // AI Provider —— 所有语言通用的 FIM 补全
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            DeepSeekCompletionProvider()
        )
    }
}

class DeepSeekCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private val LOG = Logger.getInstance(DeepSeekCompletionProvider::class.java)
    }

    private val client = DeepSeekApiClient()

    // 防止短时间内重复请求
    @Volatile private var lastRequestTime: Long = 0

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val settings = DeepSeekSettings.instance
        if (!settings.completionEnabled || settings.apiKey.isBlank()) return
        if (parameters.isExtendedCompletion) return

        // 收集前缀文本 (光标前)
        val prefix = getPrefixText(parameters)
        val prefixTrimmed = prefix.trimEnd()

        // 最少字符数检测
        val lastLine = prefixTrimmed.lines().lastOrNull() ?: ""
        if (lastLine.length < settings.completionMinPrefix) return

        // --- 2. 去抖 (debounce) ---
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestTime
        if (elapsed < settings.completionDelayMs && elapsed > 0) return
        lastRequestTime = now

        // --- 3. 收集后缀文本 (光标后) ---
        val suffix = getSuffixText(parameters)

        // --- 4. 收集语言和文件上下文 ---
        val language = getLanguage(parameters)
        val fileContext = collectFileContext(parameters, settings.maxContextLines)

        LOG.info("AI Completion triggered | lang=$language | prefixLen=${prefix.length} | suffixLen=${suffix.length}")

        // --- 5. 异步调用 FIM API (非阻塞!) ---
        client.completionFim(prefix, suffix, language, fileContext) { suggestionRaw ->
            if (suggestionRaw.isNullOrBlank()) return@completionFim

            // 清理补全结果
            val suggestion = cleanSuggestion(suggestionRaw, lastLine)

            if (suggestion.isBlank()) return@completionFim

            // 回到 EDT 写入补全结果
            ApplicationManager.getApplication().invokeLater {
                try {
                    result.addElement(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(suggestion)
                                .withTypeText("DeepSeek AI", true)
                                .withIcon(com.intellij.icons.AllIcons.Actions.Find),
                            Double.MAX_VALUE
                        )
                    )
                    LOG.info("AI Completion added | length=${suggestion.length} | preview=${suggestion.take(40).replace("\n","\\n")}")
                } catch (e: Exception) {
                    LOG.warn("Failed to add AI completion element", e)
                }
            }
        }
    }

    // ===================== 上下文收集 =====================

    /**
     * 获取光标前的代码文本 (最多取 maxContextLines 行).
     */
    private fun getPrefixText(parameters: CompletionParameters): String {
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineCount = document.getLineCount()
        val cursorLine = document.getLineNumber(offset)
        val settings = DeepSeekSettings.instance

        // 从光标往前取 N 行
        val startLine = maxOf(0, cursorLine - settings.maxContextLines)
        val startOffset = document.getLineStartOffset(startLine)
        return document.getText(com.intellij.openapi.util.TextRange(startOffset, offset))
    }

    /**
     * 获取光标后的代码文本 (最多取 20 行).
     */
    private fun getSuffixText(parameters: CompletionParameters): String {
        val document = parameters.editor.document
        val offset = parameters.offset
        val lineCount = document.getLineCount()
        val cursorLine = document.getLineNumber(offset)

        val endLine = minOf(lineCount - 1, cursorLine + 20)
        if (endLine <= cursorLine) return ""

        val endOffset = document.getLineEndOffset(endLine)
        return document.getText(com.intellij.openapi.util.TextRange(offset, endOffset))
    }

    /**
     * 获取文件语言名.
     */
    private fun getLanguage(parameters: CompletionParameters): String {
        val fileType = parameters.originalFile.fileType
        return when {
            !fileType.isBinary && fileType !is PlainTextFileType -> fileType.name
            else -> inferLanguageFromFile(parameters.originalFile.name)
        }
    }

    private fun inferLanguageFromFile(fileName: String): String {
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

    /**
     * 收集文件级上下文: 包声明, imports, 外层类/函数签名.
     */
    private fun collectFileContext(parameters: CompletionParameters, maxLines: Int): String {
        val document = parameters.editor.document
        val offset = parameters.offset
        val cursorLine = document.getLineNumber(offset)

        val sb = StringBuilder()

        // 文件头 (package, imports) — 最多前 20 行
        val headerEnd = minOf(20, cursorLine)
        for (i in 0 until headerEnd) {
            val lineStart = document.getLineStartOffset(i)
            val lineEnd = document.getLineEndOffset(i)
            val line = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))
            if (line.isNotBlank()) {
                sb.appendLine(line)
            }
        }

        // 如果光标离文件头很近,不重复
        if (cursorLine > 20) {
            sb.appendLine("// ...")
        }

        return sb.toString()
    }

    // ===================== 结果清理 =====================

    /**
     * 清理模型输出: 去 markdown, 去重复前缀, 去多余空白.
     */
    private fun cleanSuggestion(raw: String, prefixLastLine: String): String {
        var cleaned = raw

        // 去掉 markdown 代码块
        cleaned = cleaned.replace(Regex("```[\\s\\S]*?```"), "").trim()
        cleaned = cleaned.replace(Regex("^```\\w*\\s*", RegexOption.MULTILINE), "")
        cleaned = cleaned.replace(Regex("```\\s*$", RegexOption.MULTILINE), "")

        // 去掉 "Completion:" "Here is the completion:" 等前缀
        cleaned = cleaned.replace(Regex("^(?i)\\s*(completion|here\\s+is|suggestion)\\s*[:：]\\s*"), "")

        // 如果模型重复了光标前的最后一个单词,去掉
        val lastWord = prefixLastLine.split(Regex("[\\s(){}\\[\\]=;,.]+")).lastOrNull()?.trim() ?: ""
        if (lastWord.isNotBlank() && cleaned.startsWith(lastWord)) {
            cleaned = cleaned.removePrefix(lastWord)
        }

        // 去掉前置空行
        cleaned = cleaned.trimStart()

        return cleaned
    }
}
