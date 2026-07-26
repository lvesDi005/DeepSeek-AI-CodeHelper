package com.deepseek.plugin.search

import com.deepseek.plugin.api.*

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
 * 不同于 RAG 的语义模糊匹配，本引擎基于确定性关键词与正则匹配以及按需读取，
 * 使模型能像人类程序员一样搜索、阅读、判断、再搜索。
 *
 * 用法：
 *   val engine = AgenticSearch(project)
 *   val result = engine.grep("getUserById", "*.java")
 *   val files = engine.glob("glob pattern")
 *   val content = engine.read("path/to/file.java", 10, 50)
 */
class AgenticSearch(private val project: Project) {

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
     * @param query       搜索关键词或正则表达式
     * @param filePattern 可选的文件名通配符过滤
     * @return GrepResult 包含所有匹配行
     */
    fun grep(query: String, filePattern: String? = null): GrepResult {
        val allMatches = mutableListOf<GrepMatch>()
        val roots = getSourceRoots()
        var truncated = false

        for (root in roots) {
            if (allMatches.size >= maxGrepMatches) {
                truncated = true
                break
            }
            val matches = grepInDir(root, root, query, filePattern, allMatches.size)
            allMatches.addAll(matches)
            if (allMatches.size >= maxGrepMatches) {
                truncated = true
                break
            }
        }

        // 文件数截断
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
     * @param filePath  相对于项目根目录的文件路径，如 "src/main/java/com/example/UserService.java"
     * @param startLine 可选，起始行号（1-based，包含）
     * @param endLine   可选，结束行号（1-based，包含）
     * @return ReadResult 包含文件内容
     */
    fun read(filePath: String, startLine: Int? = null, endLine: Int? = null): ReadResult {
        val basePath = project.basePath ?: return ReadResult(filePath, "", 0, 0, 0)

        // 尝试多个根目录
        val roots = getSourceRootPaths()
        val allTryPaths = listOf(basePath) + roots
        var file: File? = null

        for (root in allTryPaths) {
            val candidate = File(root, filePath)
            if (candidate.exists() && candidate.isFile) {
                file = candidate
                break
            }
            // 也直接尝试绝对路径
            val absolute = File(filePath)
            if (absolute.exists() && absolute.isFile) {
                file = absolute
                break
            }
        }

        if (file == null) {
            return ReadResult(filePath, "", 0, 0, 0)
        }

        val lines = try {
            file.readText().lines()
        } catch (e: Exception) {
            return ReadResult(filePath, "", 0, 0, 0)
        }

        val totalLines = lines.size
        val actualStart = (startLine ?: 1).coerceIn(1, totalLines) - 1  // 转 0-based
        val actualEnd = (endLine ?: totalLines).coerceIn(1, totalLines)   // 1-based inclusive

        // 截断过长的范围
        val limitedEnd = if ((actualEnd - actualStart) > maxReadLines) {
            actualStart + maxReadLines
        } else {
            actualEnd
        }

        val selectedLines = lines.subList(actualStart, limitedEnd.coerceAtMost(totalLines))
        var content = selectedLines.joinToString("\n")

        // 截断过长的字符
        if (content.length > maxReadChars) {
            content = content.take(maxReadChars) + "\n// ... (truncated ${content.length - maxReadChars} chars)"
        }

        return ReadResult(
            filePath = filePath,
            content = content,
            startLine = actualStart + 1,  // 转回 1-based
            endLine = limitedEnd,
            totalLines = totalLines
        )
    }

    // ════════════════════════════════════════════════════════════════
    //  Directory 扫描
    // ════════════════════════════════════════════════════════════════

    private fun grepInDir(
        root: VirtualFile,
        dir: VirtualFile,
        query: String,
        filePattern: String?,
        currentCount: Int
    ): List<GrepMatch> {
        if (currentCount >= maxGrepMatches) return emptyList()

        val result = mutableListOf<GrepMatch>()
        val regex = try {
            Regex(query, setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            // 非法的正则表达式，当作普通字符串
            Regex.escape(query).toRegex(RegexOption.IGNORE_CASE)
        }

        for (child in dir.children ?: return result) {
            if (result.size >= maxGrepMatches) break
            if (child.isDirectory) {
                val name = child.name
                if (name in ignoredDirs || name.startsWith(".")) continue
                result.addAll(grepInDir(root, child, query, filePattern, result.size + currentCount))
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
                    lineNumber = index + 1,  // 1-based
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

    /**
     * 将 glob 通配模式转换为正则表达式。
     * 支持: * (匹配目录内任意), ** (匹配跨目录任意), ? (单字符)
     */
    private fun globToRegex(pattern: String): Regex {
        val regexStr = buildString {
            append('^')
            var i = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                        // ** 匹配零个或多个路径段
                        append(".*")
                        i += 2
                        if (i < pattern.length && pattern[i] == '/') {
                            append("/?")
                            i++
                        }
                    }
                    c == '*' -> {
                        append("[^/]*") // 单个 * 不跨目录
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
