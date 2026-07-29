package com.deepseek.plugin.access

import com.intellij.openapi.project.Project

/**
 * Unified file access interface for project file operations.
 *
 * Q&A scan mode, Agent mode, and MCP tools all use this interface to
 * read files, list directories, and search content, ensuring consistent
 * behavior across the plugin.
 *
 * Use [ChainedFileAccess] to get automatic fallback support.
 */
interface FileAccessService {

    /** List entries in a directory. */
    fun listDirectory(dirPath: String, project: Project): List<FileEntry>

    /** Read the full text content of a file. Returns null if file not found. */
    fun readFile(filePath: String, project: Project): String?

    /** Search for a text string in project files. */
    fun searchInProject(
        query: String,
        maxResults: Int,
        project: Project,
        baseDir: String? = null
    ): List<SearchResult>
}

/** Entry in a directory listing. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0
)

/** A single search result. */
data class SearchResult(
    val filePath: String,
    val lineNumber: Int,
    val lineContent: String
)
