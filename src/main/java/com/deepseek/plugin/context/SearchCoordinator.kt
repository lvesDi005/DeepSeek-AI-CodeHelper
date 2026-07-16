package com.deepseek.plugin.context

import com.deepseek.plugin.search.ToolUseEngine
import com.intellij.openapi.project.Project

/**
 * 统一搜索路由协调器。
 *
 * 根據用户问题类型自动路由到合适的检索引擎：
 * - 代码查询（含类名/函数名/代码关键词）→ ToolUseEngine（Agentic Search：grep/glob/read 多轮）
 * - 文档查询（自然语言/配置/文档）→ RagRetriever（Lucene BM25 全文检索）
 * - 补充：ProjectContextProvider（类名后缀关联映射）
 *
 * 用法：
 *   val coordinator = SearchCoordinator(project)
 *   val result = coordinator.search("用户关于 getUserById 的问题")
 *   // result.contextText 是格式化后的上下文文本，可直接注入 system prompt
 */
class SearchCoordinator(private val project: Project) {

    private val toolUseEngine = ToolUseEngine(project)
    private val ragRetriever = RagRetriever(project)
    private val contextProvider = ProjectContextProvider(project)

    /**
     * 搜索结果封装。
     * @param contextText 格式化后的上下文文本，可直接注入 LLM system prompt
     */
    data class SearchResult(val contextText: String)

    /**
     * 执行搜索：自动判断查询类型并路由。
     *
     * @param userMessage 用户原始问题
     * @return SearchResult 包含格式化上下文，无结果时 contextText 为空字符串
     */
    fun search(userMessage: String): SearchResult {
        val settings = com.deepseek.plugin.settings.DeepSeekSettings.instance
        val isCode = isCodeQuery(userMessage)

        val codeCtx: String
        val docCtx: String

        if (isCode && settings.agenticSearchEnabled) {
            // 代码查询 → Agentic Search（单轮 grep 搜索）
            val result = toolUseEngine.execute(
                userMessage = userMessage,
                singleRound = true
            )
            codeCtx = result.searchContext
            docCtx = ""
        } else {
            codeCtx = ""
            // 非代码查询或代码搜索被禁用 → RAG 文档检索
            docCtx = ragRetriever.retrieve(userMessage)
        }

        // 补充：类名关联上下文（适用于所有查询）
        val projectCtx = contextProvider.getRelatedContext(userMessage)

        val combined = buildString {
            if (codeCtx.isNotBlank()) {
                appendLine("### Agentic Search 结果")
                appendLine(codeCtx)
                appendLine()
            }
            if (docCtx.isNotBlank()) {
                appendLine("### 文档检索结果")
                appendLine(docCtx)
                appendLine()
            }
            if (projectCtx.isNotBlank()) {
                appendLine("### 相关类文件")
                appendLine(projectCtx)
                appendLine()
            }
        }

        return SearchResult(contextText = combined.trimEnd())
    }

    // ════════════════════════════════════════════════════════════════
    //  查询类型判断
    // ════════════════════════════════════════════════════════════════

    /**
     * 判断用户查询是否与代码相关。
     * 代码查询使用 Agentic Search，非代码查询使用 RAG。
     */
    private fun isCodeQuery(query: String): Boolean {
        // 包含 CamelCase 类名/函数名
        val camelCasePattern = Regex("""\b[A-Z][a-zA-Z0-9]{2,}\b""")
        if (camelCasePattern.containsMatchIn(query)) return true

        // 包含代码关键词
        val codeKeywords = listOf(
            "class", "function", "method", "interface", "enum", "annotation",
            "import", "package", "extends", "implements", "override",
            "controller", "service", "mapper", "repository", "entity",
            "dto", "vo", "po", "bo", "config", "handler", "util",
            "getter", "setter", "constructor", "bean", "component",
            "api", "rest", "endpoint", "route", "mapping",
            "数据库", "表", "字段", "接口", "实现", "继承",
            "get", "set", "find", "search", "query", "update", "save", "delete", "create",
            // 新增：框架/技术关键词
            "spring", "mybatis", "jpa", "hibernate", "controller", "service",
            "Autowired", "RequestMapping", "GetMapping", "PostMapping"
        )
        val queryLower = query.lowercase()
        for (kw in codeKeywords) {
            if (queryLower.contains(kw)) return true
        }

        // 包含以 ., #, :: 连接的可能代码路径
        val codePathPattern = Regex("""[\w.]+\.\w+""")
        if (codePathPattern.containsMatchIn(query)) return true

        return false
    }
}
