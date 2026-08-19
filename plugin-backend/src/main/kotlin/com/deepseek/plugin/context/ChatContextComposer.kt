package com.deepseek.plugin.context

import com.deepseek.plugin.search.AgenticSearch
import java.io.File

/**
 * Pure context-building logic extracted from ChatPanel.
 * Stateless — all dependencies are passed as parameters.
 */
object ChatContextComposer {

    /** Maximum search keywords */
    const val MAX_SEARCH_KEYWORDS = 5
    /** Maximum files per keyword when building context */
    const val MAX_FILES_PER_KEYWORD = 3
    /** Maximum lines of context to include */
    const val MAX_CONTEXT_LINES = 300
    /** Maximum file size to read for context */
    const val MAX_FILE_SIZE = 15_000

    /** Source file extensions recognized for project scanning */
    private val sourceExtensions = setOf(
        "java", "kt", "kts", "xml", "json", "yaml", "yml",
        "properties", "txt", "md", "sql", "gradle", "ts", "js", "css", "html",
        "py", "go", "rs", "rb", "php", "vue", "svelte", "swift", "ktm"
    )

    fun isSourceExt(ext: String?): Boolean = ext != null && ext.lowercase() in sourceExtensions

    /**
     * Detect if the user query is about code (class names, keywords, paths)
     * rather than a general documentation question.
     */
    fun isCodeQuery(query: String): Boolean {
        if (Regex("""\b[A-Z][a-zA-Z0-9]{2,}\b""").containsMatchIn(query)) return true
        val codeKeywords = listOf(
            "class", "function", "method", "interface", "enum", "annotation",
            "import", "package", "extends", "implements", "override",
            "controller", "service", "mapper", "repository", "entity",
            "dto", "vo", "po", "bo", "config", "handler", "util",
            "getter", "setter", "constructor", "bean", "component",
            "api", "rest", "endpoint", "route", "mapping",
            "数据库", "表", "字段", "接口", "实现", "继承",
            "get", "set", "find", "search", "query", "update", "save", "delete", "create"
        )
        val queryLower = query.lowercase()
        if (codeKeywords.any { queryLower.contains(it) }) return true
        if (Regex("""[\w.]+\.\w+""").containsMatchIn(query)) return true
        return false
    }

    /**
     * Extract search keywords from a user query.
     * Sources: CamelCase names, quoted strings, dotted paths, known method prefixes.
     */
    fun extractSearchKeywords(query: String): List<String> {
        val keywords = mutableSetOf<String>()
        Regex("""\b[A-Z][a-zA-Z0-9]{2,}\b""").findAll(query).forEach { keywords.add(it.value) }
        Regex("""[""']([^""']{2,})[""']""").findAll(query).forEach { keywords.add(it.groupValues[1]) }
        Regex("""\b([a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+)\b""").findAll(query).forEach {
            keywords.add(it.value.replace(".", ""))
        }
        Regex("""\b(get|set|find|search|query|update|save|delete|create|remove|add|is|has)[A-Z][a-zA-Z0-9]+\b""")
            .findAll(query).forEach { keywords.add(it.value) }
        return keywords.toList().filter { it.length >= 2 }
    }

    /**
     * Read file contents referenced by @filename patterns in user text.
     */
    fun buildRefContext(projectDir: File?, text: String): List<String> {
        val refPattern = Regex("@([\\w.\\-/]+)")
        return refPattern.findAll(text).map { it.groupValues[1] }.toList().mapNotNull { refName ->
            projectDir?.let { dir ->
                val file = dir.resolve(refName)
                if (file.isFile && file.exists()) {
                    val content = file.readText().take(3000)
                    "## @$refName\n```\n$content\n```"
                } else null
            }
        }
    }

    /**
     * Single-round Agentic Search: extract keywords → grep → build context.
     * Used when agenticSearchMaxRounds <= 1.
     */
    fun buildCodeSearchContext(query: String, agenticSearch: AgenticSearch): String {
        val keywords = extractSearchKeywords(query)
        if (keywords.isEmpty()) return ""
        val sb = StringBuilder()
        val seen = mutableSetOf<String>()
        for (kw in keywords.take(MAX_SEARCH_KEYWORDS)) {
            if (kw in seen) continue
            seen.add(kw)
            val result = agenticSearch.grep(kw)
            if (result.matches.isEmpty()) continue
            sb.appendLine("### 搜索: `$kw`（共 ${result.totalMatches} 条匹配）")
            sb.appendLine()
            val byFile = result.matches.groupBy { it.filePath }
            for ((filePath, matches) in byFile.entries.take(5)) {
                sb.appendLine("📄 `$filePath`:")
                for (matchItem in matches.take(5)) {
                    sb.appendLine("  L${matchItem.lineNumber}: ${matchItem.lineText.take(150)}")
                }
                if (matches.size > 5) sb.appendLine("  ... (还有 ${matches.size - 5} 条)")
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    /**
     * Build related file context for Agent mode: grep keywords → read matching files.
     */
    fun buildRelatedFileContext(userText: String, projectBasePath: String, agenticSearch: AgenticSearch): String {
        val keywords = extractSearchKeywords(userText)
        if (keywords.isEmpty()) return ""
        val seen = mutableSetOf<String>()
        val sb = StringBuilder()
        for (kw in keywords.take(MAX_SEARCH_KEYWORDS)) {
            if (kw in seen) continue
            seen.add(kw)
            val result = agenticSearch.grep(kw)
            if (result.matches.isEmpty()) continue
            val byFile = result.matches.groupBy { it.filePath }
            for ((filePath, matches) in byFile.entries.take(MAX_FILES_PER_KEYWORD)) {
                if (sb.count { it == '\n' } > MAX_CONTEXT_LINES) return sb.toString()
                val file = File(filePath)
                if (!file.exists() || !file.isFile) continue
                val content = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
                if (content.length > MAX_FILE_SIZE) continue
                val relativePath = file.toRelativeString(File(projectBasePath))
                sb.appendLine("--- $relativePath ---")
                sb.appendLine(content.trim())
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    /**
     * Truncate all ```code blocks``` to [maxLines] lines for display purposes.
     */
    fun truncateCodeBlocks(text: String, maxLines: Int = 20): String {
        val codeBlockRegex = Regex("""```(\w*)\s*\n?([\s\S]*?)```""")
        return codeBlockRegex.replace(text) { match ->
            val lang = match.groupValues[1]
            val code = match.groupValues[2]
            val lines = code.lines()
            if (lines.size > maxLines) {
                val truncated = lines.take(maxLines).joinToString("\n")
                "```$lang\n$truncated\n......\n```"
            } else {
                match.value
            }
        }
    }
}