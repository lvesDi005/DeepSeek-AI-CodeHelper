package com.deepseek.plugin.access

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Primary implementation of [FileAccessService] using IntelliJ's VirtualFile API.
 *
 * This is the same logic that powers the MCP built-in tools (read_file,
 * list_directory, search_in_project), extracted into a reusable service.
 */
class McpFileAccessImpl : FileAccessService {

    private val logger = logger<McpFileAccessImpl>()

    override fun listDirectory(dirPath: String, project: Project): List<FileEntry> {
        val fullPath = resolvePath(project, dirPath)
        val entries = mutableListOf<FileEntry>()

        ApplicationManager.getApplication().runReadAction {
            val file = LocalFileSystem.getInstance().findFileByPath(fullPath)
            if (file != null && file.exists() && file.isDirectory) {
                for (child in file.children) {
                    entries.add(FileEntry(
                        name = child.name,
                        path = child.path,
                        isDirectory = child.isDirectory,
                        size = if (child.isDirectory) 0 else child.length
                    ))
                }
            }
        }
        return entries.sortedBy { it.name }
    }

    override fun readFile(filePath: String, project: Project): String? {
        val fullPath = resolvePath(project, filePath)
        var content: String? = null

        ApplicationManager.getApplication().runReadAction {
            val file = LocalFileSystem.getInstance().findFileByPath(fullPath)
            if (file != null && file.exists() && !file.isDirectory) {
                try {
                    content = String(file.contentsToByteArray(), Charsets.UTF_8)
                } catch (e: Exception) {
                    logger.warn("Failed to read file: $filePath", e)
                }
            }
        }
        return content
    }

    override fun searchInProject(
        query: String,
        maxResults: Int,
        project: Project,
        baseDir: String?
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val basePath = baseDir ?: project.basePath ?: return emptyList()

        ApplicationManager.getApplication().runReadAction {
            searchInDirectory(File(basePath), query, maxResults, results, basePath)
        }
        return results
    }

    private fun resolvePath(project: Project, path: String): String {
        return if (File(path).isAbsolute) path
        else "${project.basePath ?: ""}/${path.removePrefix("/")}"
    }

    private fun searchInDirectory(
        dir: File,
        query: String,
        maxResults: Int,
        results: MutableList<SearchResult>,
        basePath: String
    ) {
        if (results.size >= maxResults) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (results.size >= maxResults) return
            if (child.isDirectory) {
                if (child.name.startsWith(".") || child.name in SKIP_DIRS) continue
                searchInDirectory(child, query, maxResults, results, basePath)
            } else if (child.isFile) {
                searchInFile(child, query, results, maxResults, basePath)
            }
        }
    }

    private fun searchInFile(
        file: File,
        query: String,
        results: MutableList<SearchResult>,
        maxResults: Int,
        basePath: String
    ) {
        if (file.extension.lowercase() in BINARY_EXTS) return
        if (file.length() > MAX_FILE_SIZE) return

        try {
            val lines = file.readLines(Charsets.UTF_8)
            val relativePath = file.path.replace("\\", "/")
            for ((index, line) in lines.withIndex()) {
                if (results.size >= maxResults) return
                if (line.contains(query, ignoreCase = true)) {
                    results.add(SearchResult(relativePath, index + 1, line.trim().take(200)))
                }
            }
        } catch (_: Exception) {
            // skip unreadable files
        }
    }

    companion object {
        private val SKIP_DIRS = setOf("build", "target", "node_modules", "out", ".gradle", ".git", "dist")
        private val BINARY_EXTS = setOf("class", "jar", "png", "jpg", "jpeg", "gif", "pdf", "zip", "exe", "dll", "so", "dylib")
        private const val MAX_FILE_SIZE = 512 * 1024
    }
}
