package com.deepseek.plugin.search

import com.deepseek.plugin.api.*

import com.deepseek.plugin.access.ChainedFileAccess
import com.deepseek.plugin.access.FileAccessService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File

/**
 * Agentic Search 引擎。
 *
 * 提供 grep / glob / read 三个搜索工具，供 LLM 自主调用以检索代码信息。
 *
 * grep 和 read 委托给 [FileAccessService]（统一文件访问层），与 MCP 工具
 * 和 Q&A 全文扫描共享同一套实现。若 FileAccessService 调用失败会自动降级。
 *
 * glob 保持独立实现（纯模式匹配，无需文件读取）。
 *
 * 用法：
 *   val engine = AgenticSearch(project)
 *   val result = engine.grep("getUserById", "*.java")
 *   val files = engine.glob("glob pattern")
 *   val content = engine.read("path/to/file.java", 10, 50)
 */
class AgenticSearch(
    private val project: Project,
    private val fileAccess: FileAccessService = ChainedFileAccess()
) {

    // ── 最大行数限制（防止单文件过大撑爆上下文） ──
    private val maxReadLines = 500
    private val maxReadChars = 30_000

    // ── grep 最大匹配数（防止匹配过多无法处理） ──
    private val maxGrepMatches = 100
    private val maxGrepFiles = 30

    // ── 忽略的目录 ──
    private val ignoredDirs = setOf(".git", ".gradle", "build", "target", "node_modules",
        "out", "dist", ".idea", ".mvn", ".settings", "bin", "obj", "__pycache__", "venv")

    // ── 支持的代码文件扩展名 ──
    private val codeExtensions = setOf("java", "kt", "kts", "scala", "groovy",
        "py", "js", "ts", "jsx", "tsx", "vue", "go", "rs", "rb", "php",
        "swift", "c", "cpp", "h", "hpp", "cs", "dart", "sql", "gradle",
        "xml", "yaml", "yml", "json", "properties", "css", "scss", "less")

    // ════════════════════════════════════════════════════════════════
    //  公开 API
    // ════════════════════════════════════════════════════════════════

    /**
     * 内容搜索（类似 ripgrep）。
     *
     * 委托给 [FileAccessService.searchInProject]，失败时自动降级到
     * VirtualFile 递归扫描。
     *
     * @param query       搜索关键词或正则表达式
     * @param filePattern 可选的文件名通配符过滤
     * @return GrepResult 包含所有匹配行
     */
    fun grep(query: String, filePattern: String? = null): GrepResult {
        val basePath = project.basePath ?: return GrepResult(query, emptyList(), 0, false)
        val regex = try {
            Regex(query, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            Regex.escape(query).toRegex(RegexOption.IGNORE_CASE)
        }

        // 先尝试通过 FileAccessService 搜索
        try {
            val searchResults = fileAccess.searchInProject(query, maxGrepMatches, project, basePath)
            if (searchResults.isNotEmpty()) {
                val matches = searchResults.map { sr ->
                    val matchResult = regex.find(sr.lineContent) ?: return@map null
                    GrepMatch(
                        filePath = sr.filePath,
                        lineNumber = sr.lineNumber,
                        lineText = sr.lineContent,
                        matchStart = matchResult.range.first,
                        matchEnd = matchResult.range.last + 1
                    )
                }.filterNotNull()

                if (matches.isNotEmpty()) {
                    val filesInResult = matches.map { it.filePath }.distinct()
                    return GrepResult(
                        query = query,
                        matches = matches.take(maxGrepMatches),
                        totalMatches = matches.size,
                        truncated = filesInResult.size > maxGrepFiles
                    )
                }
            }
        } catch (_: Exception) {
            // FileAccessService failed, fall through to legacy
        }

        // 降级：旧版 VirtualFile 递归扫描
        return grepLegacy(query, filePattern, regex)
    }

    private fun grepLegacy(query: String, filePattern: String?, regex: Regex): GrepResult {
        val allMatches = mutableListOf<GrepMatch>()
        val roots = getSourceRoots()
        var truncated = false

        for (root in roots) {
            if (allMatches.size >= maxGrepMatches) {
                truncated = true
                break
            }
            val matches = grepInDir(root, root, regex, filePattern, allMatches.size)
            allMatches.addAll(matches)
            if (allMatches.size >= maxGrepMatches) {
                truncated = true
                break
            }
        }

        val filesInResult = allMatches.map { it.filePath }.distinct()
        if (filesInResult.size > maxGrepFiles) {
            truncated = true
        }

        return GrepResult(
            query = query,
            matches = allMatches.take(maxGrepMatches),
            totalMatches = allMatches.size,
            truncated = truncated
        )
    }

    /**
     * 按文件名模式搜索文件。
     *
     * @param pattern 文件通配模式
     * @return GlobResult 包含匹配的文件路径列表
     */
    fun glob(pattern: String): GlobResult {
        val matchedFiles = mutableListOf<String>()
        val roots = getSourceRoots()

        val regexPattern = globToRegex(pattern)

        for (root in roots) {
            collectFiles(root, root, regexPattern, matchedFiles)
        }

        return GlobResult(
            pattern = pattern,
            files = matchedFiles.sorted()
        )
    }

    /**
     * 读取指定文件的内容。
     *
     * 委托给 [FileAccessService.readFile]，失败时降级到 File.readText().
     *
     * @param filePath  相对于项目根目录的文件路径
     * @param startLine 可选，起始行号（1-based，包含）
     * @param endLine   可选，结束行号（1-based，包含）
     * @return ReadResult 包含文件内容
     */
    fun read(filePath: String, startLine: Int? = null, endLine: Int? = null): ReadResult {
        val basePath = project.basePath ?: return ReadResult(filePath, "", 0, 0, 0)

        // 先尝试通过 FileAccessService 读取
        try {
            val content = fileAccess.readFile(filePath, project)
            if (content != null) {
                return buildReadResult(filePath, content, startLine, endLine)
            }
        } catch (_: Exception) {
            // fall through
        }

        // 降级：旧版直接文件读取
        return readLegacy(filePath, startLine, endLine)
    }

    private fun readLegacy(filePath: String, startLine: Int?, endLine: Int?): ReadResult {
        val basePath = project.basePath ?: return ReadResult(filePath, "", 0, 0, 0)
        val roots = getSourceRootPaths()
        val allTryPaths = listOf(basePath) + roots
        var file: File? = null

        for (root in allTryPaths) {
            val candidate = File(root, filePath)
            if (candidate.exists() && candidate.isFile) {
                file = candidate
                break
            }
            val absolute = File(filePath)
            if (absolute.exists() && absolute.isFile) {
                file = absolute
                break
            }
        }

        if (file == null) {
            return ReadResult(filePath, "", 0, 0, 0)
        }

        val content = try {
            file.readText()
        } catch (e: Exception) {
            return ReadResult(filePath, "", 0, 0, 0)
        }

        return buildReadResult(filePath, content, startLine, endLine)
    }

    private fun buildReadResult(filePath: String, content: String, startLine: Int?, endLine: Int?): ReadResult {
        val lines = content.lines()
        val totalLines = lines.size
        val actualStart = (startLine ?: 1).coerceIn(1, totalLines) - 1
        val actualEnd = (endLine ?: totalLines).coerceIn(1, totalLines)

        val limitedEnd = if ((actualEnd - actualStart) > maxReadLines) {
            actualStart + maxReadLines
        } else {
            actualEnd
        }

        val selectedLines = lines.subList(actualStart, limitedEnd.coerceAtMost(totalLines))
        var resultContent = selectedLines.joinToString("\n")

        if (resultContent.length > maxReadChars) {
            resultContent = resultContent.take(maxReadChars) + "\n// ... (truncated ${resultContent.length - maxReadChars} chars)"
        }

        return ReadResult(
            filePath = filePath,
            content = resultContent,
            startLine = actualStart + 1,
            endLine = limitedEnd,
            totalLines = totalLines
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Directory 扫描（降级用）
    // ════════════════════════════════════════════════════════════════

    private fun grepInDir(
        root: VirtualFile,
        dir: VirtualFile,
        regex: Regex,
        filePattern: String?,
        currentCount: Int
    ): List<GrepMatch> {
        if (currentCount >= maxGrepMatches) return emptyList()
        val result = mutableListOf<GrepMatch>()

        for (child in dir.children ?: return result) {
            if (result.size >= maxGrepMatches) break
            if (child.isDirectory) {
                val name = child.name
                if (name in ignoredDirs || name.startsWith(".")) continue
                result.addAll(grepInDir(root, child, regex, filePattern, result.size + currentCount))
            } else if (isCodeFile(child) && matchesFilePattern(child, filePattern)) {
                val match = searchInFile(root, child, regex)
                if (match != null) {
                    result.addAll(match)
                }
            }
        }
        return result
    }

    private fun searchInFile(root: VirtualFile, file: VirtualFile, regex: Regex): List<GrepMatch>? {
        val relativePath = getRelativePath(root, file)
        val lines: List<String> = try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<String>> {
                    val psiFile = PsiManager.getInstance(project).findFile(file)
                    psiFile?.text?.lines() ?: String(file.contentsToByteArray(), Charsets.UTF_8).lines()
                }
            )
        } catch (e: Exception) {
            return null
        }

        val matches = mutableListOf<GrepMatch>()
        for ((index, line) in lines.withIndex()) {
            val matchResult = regex.find(line) ?: continue
            matches.add(
                GrepMatch(
                    filePath = relativePath,
                    lineNumber = index + 1,
                    lineText = line.trim(),
                    matchStart = matchResult.range.first,
                    matchEnd = matchResult.range.last + 1
                )
            )
        }
        return matches.ifEmpty { null }
    }

    private fun collectFiles(
        root: VirtualFile,
        dir: VirtualFile,
        regex: Regex,
        result: MutableList<String>
    ) {
        for (child in dir.children ?: return) {
            if (child.isDirectory) {
                val name = child.name
                if (name in ignoredDirs || name.startsWith(".")) continue
                collectFiles(root, child, regex, result)
            } else if (isCodeFile(child)) {
                val relativePath = getRelativePath(root, child)
                if (regex.containsMatchIn(relativePath)) {
                    result.add(relativePath)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════════

    private fun isCodeFile(file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext.lowercase() in codeExtensions
    }

    private fun matchesFilePattern(file: VirtualFile, pattern: String?): Boolean {
        if (pattern == null) return true
        val name = file.name
        val path = file.path
        val regex = globToRegex(pattern)
        return regex.containsMatchIn(name) || regex.containsMatchIn(path)
    }

    private fun getRelativePath(root: VirtualFile, file: VirtualFile): String {
        val rootPath = root.path.trimEnd('/')
        val filePath = file.path
        return filePath.removePrefix(rootPath).trimStart('/')
    }

    private fun getSourceRoots(): List<VirtualFile> {
        return try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<VirtualFile>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots.toList()
                }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSourceRootPaths(): List<String> {
        return try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<String>> {
                    ProjectRootManager.getInstance(project).contentSourceRoots
                        .map { it.path }
                        .toList()
                }
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun globToRegex(pattern: String): Regex {
        val regexStr = buildString {
            append('^')
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        append(".*")
                        i += 2
                        if (i < pattern.length && pattern[i] == '/') {
                            append("/?")
                            i++
                        }
                    }
                    c == '*' -> {
                        append("[^/]*")
                        i++
                    }
                    c == '?' -> {
                        append("[^/]")
                        i++
                    }
                    c == '.' -> {
                        append("\\.")
                        i++
                    }
                    c == '/' -> {
                        append("/")
                        i++
                    }
                    else -> {
                        append(Regex.escape(c.toString()))
                        i++
                    }
                }
            }
            append('$')
        }
        return Regex(regexStr, setOf(RegexOption.IGNORE_CASE))
    }
}
