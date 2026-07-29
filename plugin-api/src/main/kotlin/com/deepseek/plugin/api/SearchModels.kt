package com.deepseek.plugin.api

/**
 * grep 搜索结果中的单条匹配。
 */
data class GrepMatch(
    val filePath: String,
    val lineNumber: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int
)

/**
 * grep 搜索的整体结果。
 */
data class GrepResult(
    val query: String,
    val matches: List<GrepMatch>,
    val totalMatches: Int,
    val truncated: Boolean
)

/**
 * glob 结果。
 */
data class GlobResult(
    val pattern: String,
    val files: List<String>
)

/**
 * 读取文件内容的结果。
 */
data class ReadResult(
    val filePath: String,
    val content: String,
    val startLine: Int,
    val endLine: Int,
    val totalLines: Int
)

/**
 * 工具调用的统一返回类型。
 */
sealed class ToolResult {
    data class Grep(val result: GrepResult) : ToolResult()
    data class Glob(val result: GlobResult) : ToolResult()
    data class Read(val result: ReadResult) : ToolResult()
    data class Error(val message: String) : ToolResult()
    data class External(val text: String) : ToolResult()
}

/**
 * 从模型响应中解析出的工具调用。
 */
data class ToolCall(
    val name: String,        // "grep", "glob", "read"
    val params: Map<String, String>,
    val rawXml: String       // 原始 XML 片段，用于从响应中移除
)

/**
 * 工具调用的结果反馈。
 */
data class ToolResultMessage(
    val toolName: String,
    val params: Map<String, String>,
    val result: ToolResult
)
