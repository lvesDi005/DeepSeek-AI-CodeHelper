package com.deepseek.plugin.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import java.io.File

/**
 * Scans the project's source files and builds a class-name index.
 * When the user mentions a class, finds related files by naming convention
 * (Controller → Service → Mapper → Entity → DTO) and injects them as context.
 */
class ProjectContextProvider(private val project: Project) {

    data class SourceInfo(
        val simpleName: String,
        val relativePath: String,
        val content: String
    )

    /** suffix → priority-ordered list of suffixes to search for related classes */
    private val relationMap = mapOf(
        "controller" to listOf("service", "serviceimpl", "mapper", "dao", "repository", "", "dto", "vo", "entity", "po", "bo"),
        "service"    to listOf("serviceimpl", "mapper", "dao", "repository", "controller", "", "dto", "vo", "entity", "po", "bo"),
        "serviceimpl" to listOf("service", "mapper", "dao", "repository", "controller", "", "dto", "vo", "entity", "po", "bo"),
        "mapper"     to listOf("service", "serviceimpl", "", "entity", "po", "dto", "vo", "controller", "dao", "repository"),
        "dao"        to listOf("service", "serviceimpl", "", "entity", "po", "dto", "vo", "mapper", "repository", "controller"),
        "repository" to listOf("service", "serviceimpl", "", "entity", "po", "dto", "vo", "controller", "mapper", "dao"),
        "entity"     to listOf("service", "serviceimpl", "mapper", "dao", "repository", "controller", "dto", "vo", "po"),
        "dto"        to listOf("entity", "po", "vo", "service", "serviceimpl", "controller", "mapper"),
        "vo"         to listOf("entity", "po", "dto", "service", "serviceimpl", "controller", "mapper"),
        "po"         to listOf("entity", "dto", "vo", "service", "serviceimpl", "mapper", "dao"),
        "bo"         to listOf("entity", "po", "dto", "service", "serviceimpl", "mapper"),
        "handler"    to listOf("service", "serviceimpl", "mapper", "dao", "repository", "", "dto", "vo", "entity"),
        "config"     to listOf(""),
        "properties" to listOf(""),
    )

    /** Cache: simpleName (lowercase) → SourceInfo */
    private var cache: Map<String, SourceInfo>? = null
    private var cacheTimestamp: Long = 0
    /** 每个文件的时间戳快照 — 用于增量检测：key = file path, value = timeStamp */
    private val fileTimestamps = mutableMapOf<String, Long>()

    /**
     * Main entry: given the user's message, return a context string of related project files.
     */
    fun getRelatedContext(userMessage: String): String {
        val index = getIndex()
        val keywords = extractClassNames(userMessage)
        if (keywords.isEmpty()) return ""

        val matched = mutableSetOf<SourceInfo>()
        for (kw in keywords) {
            // Try exact match first
            val exact = index[kw.lowercase()]
            if (exact != null) {
                matched.add(exact)
                // Find related files
                matched.addAll(findRelatives(exact, index))
            } else {
                // Try partial match — any class containing the keyword
                val partials = index.filter { it.key.contains(kw.lowercase()) }
                for ((_, info) in partials) {
                    matched.add(info)
                    matched.addAll(findRelatives(info, index))
                }
            }
        }

        if (matched.isEmpty()) return ""
        return buildContextString(matched.toList())
    }

    // --------------- internal ---------------

    private fun getIndex(): Map<String, SourceInfo> {
        val now = System.currentTimeMillis()
        // 60000ms (1 min) 内不触发全量重建
        if (cache != null && (now - cacheTimestamp) < 60_000) {
            // 增量检查：只在文件时间戳变化时重新读取变更文件
            val dirtyPaths = detectDirtyFiles()
            if (dirtyPaths.isEmpty()) return cache!!
            // 增量更新：只重新读取 dirty 文件
            incrementalUpdate(dirtyPaths)
            cacheTimestamp = now
            return cache!!
        }

        // 全量重建
        return rebuildIndex(now)
    }

    /** 检测哪些源文件的时间戳发生了变化 */
    private fun detectDirtyFiles(): Set<String> {
        val dirty = mutableSetOf<String>()
        try {
            val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots
            for (root in contentRoots) {
                checkDirty(root, root, dirty)
            }
        } catch (_: Exception) {}
        return dirty
    }

    private fun checkDirty(root: VirtualFile, dir: VirtualFile, dirty: MutableSet<String>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                val name = child.name
                if (name == "resources" || name == "test" || name == "META-INF" ||
                    name.startsWith(".")) continue
                checkDirty(root, child, dirty)
            } else if (isSourceFile(child)) {
                val path = child.path
                val cached = fileTimestamps[path]
                val current = child.timeStamp
                if (cached == null || cached != current) {
                    dirty.add(path)
                }
            }
        }
    }

    /** 全量重建索引 */
    private fun rebuildIndex(now: Long): Map<String, SourceInfo> {
        val result = mutableMapOf<String, SourceInfo>()
        fileTimestamps.clear()
        val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        for (root in contentRoots) {
            collectSources(root, root, result)
        }
        cache = result
        cacheTimestamp = now
        return result
    }

    /** 增量更新：只重新读取 dirty 列表中的文件 */
    private fun incrementalUpdate(dirtyPaths: Set<String>) {
        if (cache == null) return
        val mutableCache = cache!!.toMutableMap()
        val contentRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        val rootPaths = contentRoots.map { it.path }

        for (dirtyPath in dirtyPaths) {
            // 找到这个文件所属的 root
            val rootPath = rootPaths.firstOrNull { dirtyPath.startsWith(it) } ?: continue
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(dirtyPath) ?: continue
            if (!vf.exists()) {
                // 文件被删除 → 从缓存中移除
                val key = vf.nameWithoutExtension.lowercase()
                mutableCache.remove(key)
                fileTimestamps.remove(dirtyPath)
                continue
            }

            val simpleName = vf.nameWithoutExtension
            val relativePath = dirtyPath.substring(rootPath.length + 1)
            val content = readContent(vf)
            fileTimestamps[dirtyPath] = vf.timeStamp
            if (content.isNotEmpty()) {
                mutableCache[simpleName.lowercase()] = SourceInfo(simpleName, relativePath, content)
            }
        }
        cache = mutableCache
    }

    private fun collectSources(root: VirtualFile, dir: VirtualFile, result: MutableMap<String, SourceInfo>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                val name = child.name
                // Skip non-source dirs
                if (name == "resources" || name == "test" || name == "META-INF" ||
                    name.startsWith(".")) continue
                collectSources(root, child, result)
            } else if (isSourceFile(child)) {
                val simpleName = child.nameWithoutExtension
                val relativePath = child.path.substring(root.path.length + 1)
                val content = readContent(child)
                if (content.isNotEmpty()) {
                    result[simpleName.lowercase()] = SourceInfo(simpleName, relativePath, content)
                }
                // 记录时间戳用于增量检测
                fileTimestamps[child.path] = child.timeStamp
            }
        }
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        val ext = file.extension ?: return false
        return ext == "java" || ext == "kt" || ext == "xml" || ext == "yaml" || ext == "yml" || ext == "properties"
    }

    private fun readContent(file: VirtualFile): String {
        return try {
            // PSI 访问必须在 ReadAction 中执行，确保线程安全
            val psiText = ApplicationManager.getApplication().runReadAction(Computable<String> {
                val psiFile = PsiManager.getInstance(project).findFile(file)
                psiFile?.text
            })
            psiText ?: String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Extract likely class/entity names from a user message.
     * Matches: CamelCase words, words ending with known suffixes.
     */
    private fun extractClassNames(message: String): List<String> {
        val knownSuffixes = listOf(
            "controller", "service", "serviceimpl", "mapper", "dao", "repository",
            "entity", "dto", "vo", "po", "bo", "handler", "config", "util", "helper",
            "factory", "provider", "manager", "converter", "listener", "filter",
            "interceptor", "aspect", "advice", "scheduler", "job"
        )

        val camelPattern = Regex("\\b([A-Z][a-zA-Z0-9]*)\\b")
        val matches = camelPattern.findAll(message).map { it.value }.toList()

        if (matches.isNotEmpty()) return matches

        // Fallback: look for words that might be entity names
        val words = message.split(Regex("[\\s,，。.!！?？:：()（）\\[\\]【】\"'`]+"))
            .filter { it.length >= 2 }

        return words.filter { w ->
            knownSuffixes.any { w.lowercase().endsWith(it) }
        }
    }

    /**
     * Given a matched SourceInfo, find related files by stripping known suffixes
     * and searching for other files with the same base name.
     */
    private fun findRelatives(source: SourceInfo, index: Map<String, SourceInfo>): List<SourceInfo> {
        val baseName = stripSuffix(source.simpleName.lowercase()) ?: return emptyList()
        val currentSuffix = source.simpleName.lowercase().removePrefix(baseName)

        // Determine which related suffixes to search for
        val suffixesToSearch = relationMap[currentSuffix] ?: relationMap.values.flatten().distinct()

        val result = mutableListOf<SourceInfo>()
        for (suffix in suffixesToSearch) {
            if (suffix == currentSuffix) continue // skip self
            val targetName = if (suffix.isEmpty()) {
                baseName
            } else {
                baseName + suffix
            }
            val match = index[targetName]
            if (match != null && match.simpleName != source.simpleName) {
                result.add(match)
            }
        }
        return result
    }

    /**
     * Strip known suffixes from a class name to get the base entity name.
     * "usercontroller" → "user"
     * "orderdto" → "order"
     */
    private fun stripSuffix(name: String): String? {
        val suffixes = listOf(
            "serviceimpl", "controller", "service", "mapper", "repository",
            "dao", "entity", "dto", "vo", "po", "bo", "handler", "config",
            "util", "helper", "factory", "provider", "manager", "converter",
            "listener", "filter", "interceptor", "aspect", "advice", "scheduler", "job"
        )
        for (suffix in suffixes) {
            if (name.endsWith(suffix) && name.length > suffix.length) {
                return name.removeSuffix(suffix)
            }
        }
        return null
    }

    private fun buildContextString(files: List<SourceInfo>): String {
        if (files.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("以下是与当前对话相关的项目文件代码，供参考：\n")
        for (f in files.distinctBy { it.simpleName }.take(12)) {
            val lang = if (f.relativePath.endsWith(".kt")) "kotlin" else "java"
            sb.appendLine("`${f.relativePath}`:")
            sb.appendLine("```$lang")
            // Truncate very long files
            if (f.content.length > 3000) {
                sb.append(f.content.take(1500))
                sb.appendLine("\n// ... (truncated ${f.content.length - 3000} chars)")
                sb.appendLine(f.content.takeLast(1500))
            } else {
                sb.appendLine(f.content)
            }
            sb.appendLine("```\n")
        }
        return sb.toString()
    }
}
