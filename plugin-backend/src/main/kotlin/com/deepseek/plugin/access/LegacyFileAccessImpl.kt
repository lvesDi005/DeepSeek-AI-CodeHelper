package com.deepseek.plugin.access

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Legacy fallback implementation of [FileAccessService].
 *
 * Uses ProjectRootManager.contentSourceRoots to locate source roots
 * and PSI-based file reading. This preserves the original behavior
 * used by buildProjectStructure() and AgenticSearch.
 *
 * Used as the fallback in [ChainedFileAccess].
 */
class LegacyFileAccessImpl : FileAccessService {

    override fun listDirectory(dirPath: String, project: Project): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()

        ApplicationManager.getApplication().runReadAction {
            val roots = ProjectRootManager.getInstance(project).contentSourceRoots

            if (dirPath == "." || dirPath.isEmpty()) {
                for (root in roots) {
                    entries.add(FileEntry(
                        name = root.name,
                        path = root.path,
                        isDirectory = true
                    ))
                }
            } else {
                for (root in roots) {
                    findDir(root, dirPath.trimStart('/'))?.let { dir ->
                        for (child in dir.children) {
                            entries.add(FileEntry(
                                name = child.name,
                                path = child.path,
                                isDirectory = child.isDirectory,
                                size = child.length
                            ))
                        }
                    }
                }
            }
        }
        return entries.distinctBy { it.path }.sortedBy { it.name }
    }

    override fun readFile(filePath: String, project: Project): String? {
        var content: String? = null

        ApplicationManager.getApplication().runReadAction {
            try {
                val vfsFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath)
                if (vfsFile != null) {
                    val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vfsFile)
                    if (psiFile != null) {
                        content = psiFile.text
                        return@runReadAction
                    }
                }
            } catch (_: Exception) { }

            val file = java.io.File(filePath)
            if (file.exists() && file.isFile) {
                try {
                    content = file.readText(Charsets.UTF_8)
                } catch (_: Exception) { }
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
        val impl = McpFileAccessImpl()
        return impl.searchInProject(query, maxResults, project, baseDir)
    }

    private fun findDir(root: VirtualFile, relativePath: String): VirtualFile? {
        if (relativePath.isEmpty()) return root
        val parts = relativePath.split("/")
        var current = root
        for (part in parts) {
            current = current.findChild(part) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }
}
