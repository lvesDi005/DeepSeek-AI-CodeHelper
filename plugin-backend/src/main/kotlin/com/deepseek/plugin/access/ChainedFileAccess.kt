package com.deepseek.plugin.access

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Composite [FileAccessService] that tries the primary implementation first
 * and falls back to the secondary (legacy) implementation on failure.
 *
 * Usage:
 *   val fileAccess: FileAccessService = ChainedFileAccess()
 *   val content = fileAccess.readFile("src/main/...", project)
 */
class ChainedFileAccess(
    private val primary: FileAccessService = McpFileAccessImpl(),
    private val fallback: FileAccessService = LegacyFileAccessImpl()
) : FileAccessService {

    private val logger = logger<ChainedFileAccess>()

    override fun listDirectory(dirPath: String, project: Project): List<FileEntry> {
        return try {
            primary.listDirectory(dirPath, project)
        } catch (e: Exception) {
            logger.warn("FileAccess primary listDirectory failed, falling back", e)
            fallback.listDirectory(dirPath, project)
        }
    }

    override fun readFile(filePath: String, project: Project): String? {
        return try {
            primary.readFile(filePath, project)
        } catch (e: Exception) {
            logger.warn("FileAccess primary readFile failed, falling back", e)
            fallback.readFile(filePath, project)
        }
    }

    override fun searchInProject(
        query: String,
        maxResults: Int,
        project: Project,
        baseDir: String?
    ): List<SearchResult> {
        return try {
            primary.searchInProject(query, maxResults, project, baseDir)
        } catch (e: Exception) {
            logger.warn("FileAccess primary searchInProject failed, falling back", e)
            fallback.searchInProject(query, maxResults, project, baseDir)
        }
    }
}
