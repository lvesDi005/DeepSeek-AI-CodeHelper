package com.deepseek.plugin.context

import com.intellij.openapi.project.Project
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TopDocs

/**
 * BM25 检索入口（文本/文档专用）。
 * 接收用户自然语言问题，从 RagIndexer 的 Lucene 索引中召回最相关的文档文本块，
 * 按文件分组去重后格式化为上下文文本。
 *
 * 注意：代码文件的搜索已由 AgenticSearch 替代，请使用 com.deepseek.plugin.search.AgenticSearch。
 */
class RagRetriever(private val project: Project) {

    private val indexer = RagIndexer(project)
    private val retrieverLock = Any()

    // 单个块最大字符数（防止超长块撑爆上下文）
    private val maxChunkChars = 2000

    /**
     * 检索入口。
     * @param queryText 用户原始问题
     * @param topN      返回最多的 chunk 数量（默认 8）
     * @return          格式化后的上下文字符串，检索不到时返回空字符串
     */
    fun retrieve(queryText: String, topN: Int = 8): String {
        // 1. 确保索引最新
        indexer.ensureFresh()

        // 2. BM25 检索
        val results = search(queryText, topN * 2)
        if (results.isEmpty()) return ""

        // 3. 后处理：按文件分组 + 按行号排序 + 截断
        val processed = postProcess(results, topN)

        // 4. 格式化为上下文文本
        return formatContext(processed)
    }

    // ════════════════════════════════════════════════════════════════
    //  Lucene 检索（手动构建布尔查询，无需 QueryParser 依赖）
    // ════════════════════════════════════════════════════════════════

    private fun search(queryText: String, topN: Int): List<ScoredChunk> {
        synchronized(retrieverLock) {
            val reader = DirectoryReader.open(indexer.getDirectory())
            val searcher = IndexSearcher(reader)

            try {
                // 对用户问题做 Analyzer 分词，每个 token 转为 TermQuery
                val terms = tokenize(queryText)
                if (terms.isEmpty()) return emptyList()

                val booleanQuery = BooleanQuery.Builder()
                for (term in terms) {
                    booleanQuery.add(
                        TermQuery(Term("content", term)),
                        BooleanClause.Occur.SHOULD
                    )
                }

                val topDocs: TopDocs = searcher.search(booleanQuery.build(), topN)

                return topDocs.scoreDocs.map { sd: ScoreDoc ->
                    val doc = searcher.storedFields().document(sd.doc)
                    ScoredChunk(
                        chunk = CodeChunk(
                            id = doc.get("id"),
                            filePath = doc.get("filePath"),
                            startLine = doc.get("startLineStr")?.toIntOrNull() ?: 0,
                            endLine = doc.get("endLineStr")?.toIntOrNull() ?: 0,
                            content = doc.get("content") ?: ""
                        ),
                        score = sd.score
                    )
                }
            } finally {
                reader.close()
            }
        }
    }

    /**
     * 使用 StandardAnalyzer 将文本切分为 token。
     * 过滤掉停用词、过短词（<2字符）和纯数字词。
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val analyzer = indexer.getAnalyzer()
        val tokenStream = analyzer.tokenStream("content", text)
        tokenStream.reset()

        val charTermAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
        while (tokenStream.incrementToken()) {
            val term = charTermAttr.toString()
            if (term.length >= 2 && !term.all { it.isDigit() }) {
                tokens.add(term)
            }
        }
        tokenStream.end()
        tokenStream.close()
        return tokens
    }

    // ════════════════════════════════════════════════════════════════
    //  后处理 — 去重 + 按文件分组 + 按行号排序 + token 预算控制
    // ════════════════════════════════════════════════════════════════

    private data class ScoredChunk(
        val chunk: CodeChunk,
        val score: Float
    )

    private fun postProcess(results: List<ScoredChunk>, maxChunks: Int): List<CodeChunk> {
        // 按文件分组，组内按行号排序
        val grouped = results.groupBy { it.chunk.filePath }
            .mapValues { (_, chunks) ->
                chunks.sortedBy { it.chunk.startLine }
                    .distinctBy { "${it.chunk.startLine}-${it.chunk.endLine}" }
            }

        // 按文件最高分排序，取 top maxChunks 个 chunk
        val fileBestScore = grouped.mapValues { (_, chunks) ->
            chunks.maxOfOrNull { it.score } ?: 0f
        }

        val sortedFiles = fileBestScore.entries
            .sortedByDescending { it.value }

        val finalChunks = mutableListOf<CodeChunk>()
        val seenFiles = mutableSetOf<String>()

        for ((filePath, _) in sortedFiles) {
            if (finalChunks.size >= maxChunks) break
            val fileChunks = grouped[filePath] ?: continue
            for (sc in fileChunks) {
                if (finalChunks.size >= maxChunks) break
                finalChunks.add(sc.chunk)
            }
            seenFiles.add(filePath)
        }

        return finalChunks
    }

    // ════════════════════════════════════════════════════════════════
    //  格式化为上下文字符串
    // ════════════════════════════════════════════════════════════════

    private fun formatContext(chunks: List<CodeChunk>): String {
        if (chunks.isEmpty()) return ""

        val sb = StringBuilder()
        // 按文件分组再输出，避免同一文件重复出现文件头
        val byFile = chunks.groupBy { it.filePath }

        sb.appendLine("以下是项目代码中与当前问题相关的上下文：\n")

        for ((filePath, fileChunks) in byFile) {
            val sorted = fileChunks.sortedBy { it.startLine }
            sb.appendLine("📄 `$filePath`:")

            for (chunk in sorted) {
                val lineRange = "L${chunk.startLine + 1}-L${chunk.endLine + 1}" // 转 1-based
                sb.appendLine("   --- $lineRange ---")

                val content = chunk.content
                if (content.length > maxChunkChars) {
                    sb.appendLine(content.take(maxChunkChars))
                    sb.appendLine("   // ... (剩余 ${content.length - maxChunkChars} 字符)")
                } else {
                    val lang = detectLanguage(filePath)
                    if (lang != null) sb.appendLine("```$lang") else sb.appendLine("```")
                    sb.appendLine(content)
                    sb.appendLine("```")
                }
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    private fun detectLanguage(filePath: String): String? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "xml" -> "xml"
            "yaml", "yml" -> "yaml"
            "properties" -> "properties"
            "sql" -> "sql"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "vue" -> "vue"
            "css" -> "css"
            "html" -> "html"
            "md" -> "markdown"
            "gradle" -> "groovy"
            else -> null
        }
    }
}
