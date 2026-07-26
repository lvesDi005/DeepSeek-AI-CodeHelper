package com.deepseek.plugin.context

/**
 * 将代码文件按行切分为块，每块 [maxLines] 行，前后保留 [overlap] 行重叠
 * 避免方法/逻辑在边界处被硬切断，影响检索和阅读
 */
class ChunkSplitter(
    private val maxLines: Int = 60,
    private val overlap: Int = 5
) {

    fun split(filePath: String, content: String): List<CodeChunk> {
        val lines = content.lines()
        if (lines.isEmpty()) return emptyList()

        val chunks = mutableListOf<CodeChunk>()
        var start = 0
        var chunkIndex = 0

        while (start < lines.size) {
            val actualEnd = minOf(start + maxLines, lines.size)

            // 实际 chunk 范围
            val rangeStart = start
            val rangeEnd = actualEnd

            // 存储时带前后重叠上下文
            val storeStart = maxOf(0, start - overlap)
            val storeEnd = minOf(actualEnd + overlap, lines.size)
            val chunkLines = lines.subList(storeStart, storeEnd)

            chunks.add(
                CodeChunk(
                    id = "$filePath#chunk$chunkIndex",
                    filePath = filePath,
                    startLine = rangeStart,
                    endLine = rangeEnd - 1,
                    content = chunkLines.joinToString("\n")
                )
            )

            start = actualEnd
            chunkIndex++
        }

        return chunks
    }
}

/**
 * 代码文件中的一个块
 * @param id          唯一标识，格式: "相对路径#chunk序号"
 * @param filePath    相对于源码根目录的路径，如 "com/example/UserService.java"
 * @param startLine   块在文件中的起始行号（0-based）
 * @param endLine     块在文件中的结束行号（0-based）
 * @param content     块代码内容（含前后重叠行，便于阅读上下文）
 */
data class CodeChunk(
    val id: String,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String
)
