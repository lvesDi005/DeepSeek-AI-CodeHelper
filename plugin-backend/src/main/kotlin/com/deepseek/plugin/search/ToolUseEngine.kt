package com.deepseek.plugin.search

import com.deepseek.plugin.api.*

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.DOMAIN_RESTRICTION_PROMPT
import com.deepseek.plugin.api.DeepSeekApiClient
import com.intellij.openapi.project.Project

/**
 * 工具调用循环引擎。
 *
 * 实现 Agentic Search + 外部 MCP 工具的核心循环：
 * 1. 发送系统提示（含工具定义，含外部 MCP 工具）+ 用户问题 → LLM
 * 2. 解析 LLM 响应中的工具调用（XML 格式）
 * 3. 执行工具调用（grep / glob / read / 外部 MCP 工具）
 * 4. 将工具结果反馈给 LLM
 * 5. 重复 2-4 直到 LLM 给出最终答案或达到最大轮次
 *
 * 外部 MCP 工具通过 [externalToolDefinitions] 和 [externalToolExecutor] 参数集成。
 * 工具命名格式：{serverName}_{toolName}，如 "stripe_get_balance"
 */
class ToolUseEngine(
    private val project: Project,
    private val maxRounds: Int = 3,
    private val externalToolDefinitions: String = "",
    private val externalToolExecutor: ((String, Map<String, String>) -> String?)? = null
) {
    private val searchEngine = AgenticSearch(project)
    private val apiClient = DeepSeekApiClient()

    companion object {
        private val TOOL_CALL_REGEX = Regex(
            """<tool\s+name="([^"]+)"\s+params='(\{.*?\})'\s*/?>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }

    /** Build dynamic tool definitions including external MCP tools. */
    private fun buildToolDefinitions(): String {
        return buildString {
            appendLine("${DOMAIN_RESTRICTION_PROMPT}")
            appendLine()
            appendLine("你是一个代码搜索助手。你可以使用以下工具自主搜索项目的代码库来回答用户的问题。")
            appendLine()
            appendLine("## 可用工具")
            appendLine()
            appendLine("### grep(query: String, filePattern: String?) → 搜索结果")
            appendLine("对项目代码进行内容搜索（类似 ripgrep/grep）。")
            appendLine("- query: 搜索关键词或正则表达式（必填）")
            appendLine("- filePattern: 可选的路径过滤模式，如 \"*.java\", \"**/controller/*.kt\"（可选）")
            appendLine("返回匹配的文件路径、行号和内容片段。")
            appendLine()
            appendLine("### glob(pattern: String) → 文件列表")
            appendLine("按文件名模式查找文件。")
            appendLine("- pattern: 文件名模式，如 \"**/*Controller.java\", \"**/User*.kt\"")
            appendLine("返回匹配的文件路径列表。")
            appendLine()
            appendLine("### read(path: String, startLine?: String, endLine?: String) → 文件内容")
            appendLine("读取指定文件的内容片段。")
            appendLine("- path: 相对于项目根目录的文件路径（必填）")
            appendLine("- startLine: 可选，起始行号（1-based）")
            appendLine("- endLine: 可选，结束行号（1-based）")
            appendLine("返回文件内容。")
            appendLine()

            if (externalToolDefinitions.isNotBlank()) {
                appendLine(externalToolDefinitions)
                appendLine()
            }

            appendLine("## 使用规则")
            appendLine()
            appendLine("1. 当你需要搜索代码时，输出工具调用 XML。格式必须严格如下（JSON 键名必须加双引号）：")
            appendLine("   <tool name=\"grep\" params='{\"query\":\"getUserById\",\"filePattern\":\"*.java\"}'></tool>")
            appendLine("   <tool name=\"glob\" params='{\"pattern\":\"**/*Controller*\"}'></tool>")
            appendLine("   <tool name=\"read\" params='{\"path\":\"src/main/.../UserService.java\"}'></tool>")
            if (externalToolDefinitions.isNotBlank()) {
                appendLine("   外部 MCP 工具示例：")
                appendLine("   <tool name=\"stripe_get_balance\" params='{}'></tool>")
                appendLine("   <tool name=\"github_search_issues\" params='{\"query\":\"bug\"}'></tool>")
            }
            appendLine()
            appendLine("2. 每次只调用一个工具，等待结果后再决定下一步。")
            appendLine()
            appendLine("3. 搜索策略（像人类程序员一样思考）：")
            appendLine("   - 先用 grep 搜索关键函数名/类名/变量名")
            appendLine("   或用 glob 定位文件名")
            appendLine("   - 再用 read 读取具体文件内容")
            appendLine("   - 根据读到的内容决定下一步搜索方向")
            appendLine("   - 追踪 import 和调用关系来理解代码结构")
            appendLine()
            appendLine("4. 当收集到足够信息后，给出最终答案（不要输出工具调用 XML）。")
            appendLine()
            appendLine("5. 如果搜索结果为空，尝试不同的搜索词或文件模式。")
            appendLine()
            appendLine("6. 工具参数的 JSON 中字符串值必须用双引号，布尔/数值不加引号。")
        }.trimIndent()
    }

    /**
     * 使用 Agentic Search 执行一个查询。
     */
    fun execute(
        userMessage: String,
        systemPrompt: String? = null,
        singleRound: Boolean = false
    ): ExecuteResult {
        if (singleRound) {
            return executeSingleRound(userMessage, systemPrompt)
        }
        return executeMultiRound(userMessage, systemPrompt)
    }

    private fun executeSingleRound(
        userMessage: String,
        systemPrompt: String?
    ): ExecuteResult {
        val keywords = extractKeywords(userMessage)
        val grepResults = mutableListOf<GrepResult>()

        for (kw in keywords.take(3)) {
            val result = searchEngine.grep(kw)
            if (result.matches.isNotEmpty()) {
                grepResults.add(result)
            }
        }

        val searchContext = buildSearchContext(grepResults)

        val finalPrompt = buildString {
            systemPrompt?.let { appendLine(it).appendLine() }
            appendLine("以下是基于用户问题搜索到的项目代码上下文：")
            appendLine(searchContext)
            appendLine()
            appendLine("根据以上项目代码上下文，回答以下用户问题：")
            appendLine(userMessage)
        }

        return ExecuteResult(
            answer = finalPrompt,
            searchContext = searchContext,
            toolCalls = emptyList(),
            rounds = 1
        )
    }

    private fun executeMultiRound(
        userMessage: String,
        systemPrompt: String?
    ): ExecuteResult {
        val messages = mutableListOf<ChatMessage>()
        val allToolCalls = mutableListOf<ToolCall>()

        val fullSystemPrompt = buildString {
            systemPrompt?.let { appendLine(it).appendLine() }
            appendLine(buildToolDefinitions())
        }
        messages.add(ChatMessage("system", fullSystemPrompt))
        messages.add(ChatMessage("user", userMessage))

        var finalAnswer: String? = null

        for (round in 1..maxRounds) {
            val response = apiClient.chatSync(messages)
            if (response.isFailure) {
                return ExecuteResult(
                    answer = "搜索过程出错: ${response.exceptionOrNull()?.message}",
                    searchContext = "",
                    toolCalls = allToolCalls,
                    rounds = round
                )
            }

            val content = response.getOrThrow()
            val toolCall = parseToolCall(content)

            if (toolCall == null) {
                finalAnswer = content
                break
            }

            allToolCalls.add(toolCall)

            val toolResult = executeTool(toolCall)

            val textPart = content.replace(TOOL_CALL_REGEX, "").trim()
            if (textPart.isNotEmpty()) {
                messages.add(ChatMessage("assistant", textPart))
            } else {
                messages.add(ChatMessage("assistant", "[调用工具: ${toolCall.name}]"))
            }

            messages.add(ChatMessage("user", formatToolResult(toolCall, toolResult)))
        }

        val answer = finalAnswer ?: "已达到最大搜索轮次 (${maxRounds})，请根据已有搜索结果给出回答。"

        return ExecuteResult(
            answer = answer,
            searchContext = "",
            toolCalls = allToolCalls,
            rounds = allToolCalls.size
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  工具调用解析
    // ════════════════════════════════════════════════════════════════

    private fun parseToolCall(response: String): ToolCall? {
        val match = TOOL_CALL_REGEX.find(response) ?: return null
        val name = match.groupValues[1]
        val paramsJson = match.groupValues[2]

        val params = try {
            val map = mutableMapOf<String, String>()
            val entriesRegex = Regex(""""(\w+)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            for (entry in entriesRegex.findAll(paramsJson)) {
                map[entry.groupValues[1]] = entry.groupValues[2]
            }
            val simpleRegex = Regex(""""(\w+)"\s*:\s*(\d+)""")
            for (entry in simpleRegex.findAll(paramsJson)) {
                map[entry.groupValues[1]] = entry.groupValues[2]
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }

        return ToolCall(
            name = name.lowercase(),
            params = params,
            rawXml = match.value
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  工具执行
    // ════════════════════════════════════════════════════════════════

    private fun executeTool(toolCall: ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "grep" -> {
                    val query = toolCall.params["query"] ?: return ToolResult.Error("grep: missing 'query' param")
                    val filePattern = toolCall.params["filePattern"]
                    val result = searchEngine.grep(query, filePattern)
                    ToolResult.Grep(result)
                }
                "glob" -> {
                    val pattern = toolCall.params["pattern"] ?: return ToolResult.Error("glob: missing 'pattern' param")
                    val result = searchEngine.glob(pattern)
                    ToolResult.Glob(result)
                }
                "read" -> {
                    val path = toolCall.params["path"] ?: return ToolResult.Error("read: missing 'path' param")
                    val startLine = toolCall.params["startLine"]?.toIntOrNull()
                    val endLine = toolCall.params["endLine"]?.toIntOrNull()
                    val result = searchEngine.read(path, startLine, endLine)
                    ToolResult.Read(result)
                }
                else -> {
                    // Try external MCP tool
                    if (externalToolExecutor != null) {
                        val externalResult = externalToolExecutor(toolCall.name, toolCall.params)
                        if (externalResult != null) {
                            ToolResult.External(externalResult)
                        } else {
                            ToolResult.Error("未知工具: ${toolCall.name}，可用工具: grep, glob, read")
                        }
                    } else {
                        ToolResult.Error("未知工具: ${toolCall.name}，可用工具: grep, glob, read")
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult.Error("执行 ${toolCall.name} 出错: ${e.message ?: "未知错误"}")
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  结果格式化
    // ════════════════════════════════════════════════════════════════

    private fun formatToolResult(toolCall: ToolCall, result: ToolResult): String {
        val sb = StringBuilder()
        sb.appendLine("## 工具调用结果: ${toolCall.name}")
        sb.appendLine("参数: ${
            toolCall.params.entries.joinToString(", ") { "${it.key}=${it.value}" }
        }")
        sb.appendLine()

        when (result) {
            is ToolResult.Grep -> {
                val grep = result.result
                if (grep.matches.isEmpty()) {
                    sb.appendLine("无匹配结果。")
                } else {
                    sb.appendLine("共 ${grep.totalMatches} 条匹配（显示前 ${grep.matches.size} 条）:")
                    sb.appendLine()
                    sb.appendLine("| 文件 | 行号 | 内容 |")
                    sb.appendLine("|------|------|------|")
                    for (match in grep.matches.take(50)) {
                        val escapedLine = match.lineText
                            .replace("|", "\\|")
                            .take(120)
                        sb.appendLine("| `${match.filePath}` | ${match.lineNumber} | `${escapedLine}` |")
                    }
                    if (grep.truncated) {
                        sb.appendLine()
                        sb.appendLine("（结果过多已被截断，请调整搜索词缩小范围）")
                    }
                }
            }
            is ToolResult.Glob -> {
                val glob = result.result
                if (glob.files.isEmpty()) {
                    sb.appendLine("无匹配文件。")
                } else {
                    sb.appendLine("找到 ${glob.files.size} 个匹配文件:")
                    for (file in glob.files.take(50)) {
                        sb.appendLine("- `$file`")
                    }
                    if (glob.files.size > 50) {
                        sb.appendLine("（仅显示前 50 个）")
                    }
                }
            }
            is ToolResult.Read -> {
                val read = result.result
                if (read.content.isEmpty()) {
                    sb.appendLine("文件不存在或无法读取: `${read.filePath}`")
                    sb.appendLine()
                    sb.appendLine("请使用 glob 工具确认文件路径正确。")
                } else {
                    sb.appendLine("文件: `${read.filePath}`（共 ${read.totalLines} 行，显示 L${read.startLine}-${read.endLine}）:")
                    sb.appendLine()
                    sb.appendLine("```")
                    val lines = read.content.lines()
                    val lineNumWidth = read.endLine.toString().length
                    for ((i, line) in lines.withIndex()) {
                        val lineNum = read.startLine + i
                        sb.appendLine("${"$lineNum".padStart(lineNumWidth)}│$line")
                    }
                    sb.appendLine("```")
                    if (read.endLine < read.totalLines) {
                        sb.appendLine("（文件还有 ${read.totalLines - read.endLine} 行未显示，可使用 read 指定更大范围）")
                    }
                }
            }
            is ToolResult.Error -> {
                sb.appendLine("错误: ${result.message}")
            }
            is ToolResult.External -> {
                sb.appendLine(result.text)
            }
        }

        sb.appendLine()
        sb.appendLine("根据以上搜索结果，继续分析。")
        sb.appendLine("如果需要更多信息，请再次调用工具。如果已足够，请直接给出最终答案。")

        return sb.toString()
    }

    // ════════════════════════════════════════════════════════════════
    //  单轮模式辅助
    // ════════════════════════════════════════════════════════════════

    private fun extractKeywords(message: String): List<String> {
        val camelCasePattern = Regex("""\b([A-Z][a-zA-Z0-9]{1,})\b""")
        val camelMatches = camelCasePattern.findAll(message).map { it.value }.toList()

        val snakePattern = Regex("""\b([a-z][a-z0-9_]{1,}(?:get|set|find|search|update|delete|save|create|add|remove)[a-z0-9_]*)\b""")
        val snakeMatches = snakePattern.findAll(message).map { it.value }.toList()

        val quotePattern = Regex("""[""']([^""']{2,})[""']""")
        val quoteMatches = quotePattern.findAll(message).map { it.groupValues[1] }.toList()

        val identPattern = Regex("""\b([a-z][a-zA-Z0-9]{3,})\b""")
        val identMatches = identPattern.findAll(message).map { it.value }.toList()

        return (camelMatches + snakeMatches + quoteMatches + identMatches)
            .distinct()
            .filter { it.length >= 2 }
            .take(10)
    }

    private fun buildSearchContext(grepResults: List<GrepResult>): String {
        if (grepResults.isEmpty()) return "（未找到相关代码上下文）"

        val sb = StringBuilder()
        for (result in grepResults) {
            if (result.matches.isEmpty()) continue
            sb.appendLine("### 搜索: `${result.query}`")
            sb.appendLine()

            val byFile = result.matches.groupBy { it.filePath }
            for ((filePath, matches) in byFile) {
                sb.appendLine("📄 `$filePath`:")
                for (match in matches.take(10)) {
                    sb.appendLine("  L${match.lineNumber}: ${match.lineText.take(150)}")
                }
                if (matches.size > 10) {
                    sb.appendLine("  ... (还有 ${matches.size - 10} 条匹配)")
                }
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    data class ExecuteResult(
        val answer: String,
        val searchContext: String,
        val toolCalls: List<ToolCall>,
        val rounds: Int
    )
}
