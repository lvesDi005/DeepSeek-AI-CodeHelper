package com.deepseek.plugin.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntPoint
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.store.ByteBuffersDirectory
import org.apache.lucene.store.Directory
import java.io.File

/**
 * Lucene 索引构建与增量更新（文本/文档专用）。
 * 扫描项目源码根目录下的文档文本文件（.md, .txt, .rst 等），切块后建立 BM25 倒排索引。
 *
 * 用途：为 RAG 提供非结构化文本检索能力，适用于 README、文档、配置说明等。
 * 注意：代码文件的搜索已由 AgenticSearch（grep/glob/read 工具）替代。
 * 详见 com.deepseek.plugin.search.AgenticSearch。
 *
 * 索引策略：
 * - 存在 ByteBuffersDirectory（堆内内存），项目关闭即释放
 * - 首次调用 buildIndex 全量构建
 * - 可通过 onFileSaved 增量更新单个文件
 * - 内置脏检测：detectDirtyFiles 对比文件时间戳
 */
class RagIndexer(private val project: Project) {

    /** Lucene 内存目录，同一目录可被多个 IndexReader 安全共享 */
    private val directory: Directory = ByteBuffersDirectory()

    private val analyzer = StandardAnalyzer()
    private val splitter = ChunkSplitter()
    private val indexerLock = Any()

    /** 文件时间戳缓存，用于脏检测 */
    private val fileTimestamps = mutableMapOf<String, Long>()
    /** 最近一次全量/增量更新的时间戳 */
    private var lastBuildTime: Long = 0
    /** 最小重建间隔（毫秒） */
    private val rebuildIntervalMs = 30_000L

    // ── 支持的源文件类型（仅文本/文档类文件） ──
    private val supportedExtensions = setOf("md", "txt", "rst", "adoc", "asciidoc",
        "markdown", "html", "htm", "xml", "yaml", "yml", "properties", "json", "toml",
        "ini", "cfg", "conf")

    // ── 公开方法 ──

    /** 获取 Lucene 目录，供 Retriever 打开 IndexReader */
    fun getDirectory(): Directory = directory

    /** 获取分析器，供 Retriever 构建 Query 时使用 */
    fun getAnalyzer(): StandardAnalyzer = analyzer

    /**
     * 确保索引已构建且不陈旧。
     * 满足以下任一条件会触发重建：距上次构建超过 30 秒 / 有文件变更
     */
    fun ensureFresh() {
        val now = System.currentTimeMillis()
        if (now - lastBuildTime < rebuildIntervalMs) {
            // 时间窗口内做增量脏检测
            val dirty = detectDirtyFiles()
            if (dirty.isEmpty()) return
            incrementalUpdate(dirty)
            lastBuildTime = now
            return
        }
        buildIndex()
    }

    // ── 全量构建 ──

    private fun buildIndex() {
        synchronized(indexerLock) {
            val writer = createIndexWriter()
            writer.deleteAll()
            fileTimestamps.clear()

            val sourceRoots = getSourceRootPaths()
            for (root in sourceRoots) {
                val rootFile = File(root)
                if (rootFile.exists()) {
                    collectFiles(rootFile, rootFile, writer)
                }
            }
            writer.commit()
            writer.close()
            lastBuildTime = System.currentTimeMillis()
        }
    }

    // ── 增量更新 ──

    private fun incrementalUpdate(dirtyPaths: Set<String>) {
        synchronized(indexerLock) {
            val writer = createIndexWriter()

            for (dirtyPath in dirtyPaths) {
                val file = File(dirtyPath)
                if (!file.exists()) {
                    // 文件被删除 → 清除对应 chunk
                    val relativePath = getRelativePath(file)
                    writer.deleteDocuments(Term("filePath", relativePath))
                    fileTimestamps.remove(dirtyPath)
                    continue
                }
                if (!isSupportedFile(file)) continue

                val relativePath = getRelativePath(file)
                val content = file.readText()
                // 删除旧 chunk
                writer.deleteDocuments(Term("filePath", relativePath))
                // 写入新 chunk
                val chunks = splitter.split(relativePath, content)
                for (chunk in chunks) {
                    writer.addDocument(toLuceneDoc(chunk))
                }
                fileTimestamps[dirtyPath] = file.lastModified()
            }

            writer.commit()
            writer.close()
            lastBuildTime = System.currentTimeMillis()
        }
    }

    // ── 脏文件检测（复用时间戳方案） ──

    private fun detectDirtyFiles(): Set<String> {
        val dirty = mutableSetOf<String>()
        val sourceRoots = getSourceRootPaths()
        for (root in sourceRoots) {
            val rootFile = File(root)
            if (rootFile.exists()) {
                checkDirty(rootFile, rootFile, dirty)
            }
        }
        return dirty
    }

    private fun checkDirty(root: File, dir: File, dirty: MutableSet<String>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name == "build" || name == "target" ||
                    name == "node_modules" || name == ".git" || name == "out" ||
                    name == "dist" || name == ".gradle"
                ) continue
                checkDirty(root, child, dirty)
            } else if (isSupportedFile(child)) {
                val path = child.absolutePath
                val cached = fileTimestamps[path]
                val current = child.lastModified()
                if (cached == null || cached != current) {
                    dirty.add(path)
                }
            }
        }
    }

    // ── 文件收集（全量构建用） ──

    private fun collectFiles(root: File, dir: File, writer: IndexWriter) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                val name = child.name
                if (name.startsWith(".") || name == "build" || name == "target" ||
                    name == "node_modules" || name == ".git" || name == "out" ||
                    name == "dist" || name == ".gradle"
                ) continue
                collectFiles(root, child, writer)
            } else if (isSupportedFile(child)) {
                try {
                    val relativePath = child.absolutePath
                        .removePrefix(root.absolutePath)
                        .trimStart(File.separatorChar)
                    val content = child.readText()
                    fileTimestamps[child.absolutePath] = child.lastModified()
                    val chunks = splitter.split(relativePath, content)
                    for (chunk in chunks) {
                        writer.addDocument(toLuceneDoc(chunk))
                    }
                } catch (_: Exception) {
                    // 跳过无法读取的文件
                }
            }
        }
    }

    // ── 工具方法 ──

    /** 通过 IntelliJ API 获取源码根目录路径 */
    private fun getSourceRootPaths(): List<String> {
        val paths = mutableListOf<String>()
        try {
            val roots = ApplicationManager.getApplication().runReadAction(
                Computable<List<VirtualFile>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots.toList()
                }
            )
            for (root in roots) {
                paths.add(root.path.replace('/', File.separatorChar))
            }
        } catch (_: Exception) {}
        return paths
    }

    /** 获取文件相对于项目根或源码根的路径 */
    private fun getRelativePath(file: File): String {
        val base = project.basePath ?: return file.name
        return file.absolutePath.removePrefix(base).trimStart(File.separatorChar)
    }

    private fun isSupportedFile(file: File): Boolean {
        val ext = file.extension ?: return false
        return ext.lowercase() in supportedExtensions
    }

    private fun createIndexWriter(): IndexWriter {
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
            // 限制 Lucene 不要消耗太多内存
            ramBufferSizeMB = 64.0
        }
        return IndexWriter(directory, config)
    }

    private fun toLuceneDoc(chunk: CodeChunk): Document {
        return Document().apply {
            add(StringField("id", chunk.id, Field.Store.YES))
            add(StringField("filePath", chunk.filePath, Field.Store.YES))
            add(IntPoint("startLine", chunk.startLine))
            add(IntPoint("endLine", chunk.endLine))
            // 用于显示的存储字段
            add(StringField("startLineStr", chunk.startLine.toString(), Field.Store.YES))
            add(StringField("endLineStr", chunk.endLine.toString(), Field.Store.YES))
            // 正文 — 分词+存储，支持 BM25 全文检索
            add(TextField("content", chunk.content, Field.Store.YES))
        }
    }
}
