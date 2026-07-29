package com.deepseek.plugin.mcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.deepseek.plugin.access.ChainedFileAccess
import com.deepseek.plugin.access.FileAccessService
import com.deepseek.plugin.mcp.protocol.McpToolResult
import com.deepseek.plugin.mcp.tool.InputSchema
import com.deepseek.plugin.mcp.tool.McpToolDefinition
import com.deepseek.plugin.mcp.tool.McpToolProvider

/**
 * Built-in file operation tools: read_file, write_file, list_directory.
 *
 * read_file and list_directory delegate to [FileAccessService] for unified
 * file access behavior shared with Q&A scan mode and Agent mode.
 */
class FileToolProvider : McpToolProvider {

    private val fileAccess: FileAccessService = ChainedFileAccess()

    override fun getTools(): List<McpToolDefinition> = listOf(
        ReadFileTool(fileAccess),
        WriteFileTool(),
        ListDirectoryTool(fileAccess)
    )
}

class ReadFileTool(
    private val fileAccess: FileAccessService
) : McpToolDefinition(
    name = "read_file",
    description = "Read the content of a file in the project. Path is relative to the project root.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "path" to InputSchema.PropertyDef("string", "File path relative to project root, or absolute path"),
            "project" to InputSchema.PropertyDef("string", "Project name (optional, uses first open project if not specified)")
        ),
        required = listOf("path")
    )
) {
    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val path = arguments["path"] as? String
            ?: return McpToolResult.error("Missing 'path' parameter")

        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        val content = fileAccess.readFile(path, project)
        return if (content != null) {
            McpToolResult.text(content)
        } else {
            McpToolResult.error("File not found or unreadable: $path")
        }
    }
}

class WriteFileTool : McpToolDefinition(
    name = "write_file",
    description = "Write content to a file in the project. Creates the file if it does not exist.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "path" to InputSchema.PropertyDef("string", "File path relative to project root, or absolute path"),
            "content" to InputSchema.PropertyDef("string", "Content to write to the file"),
            "project" to InputSchema.PropertyDef("string", "Project name (optional)")
        ),
        required = listOf("path", "content")
    )
) {
    private val logger = logger<WriteFileTool>()

    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val path = arguments["path"] as? String
            ?: return McpToolResult.error("Missing 'path' parameter")
        val content = arguments["content"] as? String
            ?: return McpToolResult.error("Missing 'content' parameter")

        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        val fullPath = ProjectUtils.resolvePath(project, path)
        var result: McpToolResult = McpToolResult.error("Write failed")

        WriteCommandAction.runWriteCommandAction(project) {
            try {
                val lfs = LocalFileSystem.getInstance()
                val javaFile = java.io.File(fullPath)

                if (!javaFile.parentFile.exists()) {
                    javaFile.parentFile.mkdirs()
                }

                if (!javaFile.exists()) {
                    javaFile.createNewFile()
                }

                val virtualFile = lfs.refreshAndFindFileByIoFile(javaFile)
                if (virtualFile == null) {
                    result = McpToolResult.error("Could not access file: $path")
                    return@runWriteCommandAction
                }

                VfsUtil.saveText(virtualFile, content)
                result = McpToolResult.text("File written successfully: $path (${content.length} chars)")
            } catch (e: Exception) {
                logger.error("Failed to write file: $path", e)
                result = McpToolResult.error("Failed to write file: ${e.message}")
            }
        }
        return result
    }
}

class ListDirectoryTool(
    private val fileAccess: FileAccessService
) : McpToolDefinition(
    name = "list_directory",
    description = "List files and subdirectories in a directory. Path is relative to the project root.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "path" to InputSchema.PropertyDef("string", "Directory path relative to project root (use '.' for project root)"),
            "project" to InputSchema.PropertyDef("string", "Project name (optional)")
        ),
        required = listOf("path")
    )
) {
    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val path = arguments["path"] as? String
            ?: return McpToolResult.error("Missing 'path' parameter")

        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        val entries = fileAccess.listDirectory(path, project)
        if (entries.isEmpty()) {
            return McpToolResult.error("Directory not found or empty: $path")
        }

        val text = entries.joinToString("\n") { entry ->
            val type = if (entry.isDirectory) "[DIR] " else "      "
            "$type${entry.name}"
        }
        return McpToolResult.text(text)
    }
}

/** Utility for project access from tools. */
object ProjectUtils {
    fun getProject(name: String? = null) = if (name != null) {
        ProjectManager.getInstance().openProjects.firstOrNull { it.name == name }
    } else {
        ProjectManager.getInstance().openProjects.firstOrNull()
    }

    fun resolvePath(project: com.intellij.openapi.project.Project, relativePath: String): String {
        val basePath = project.basePath ?: ""
        return if (java.io.File(relativePath).isAbsolute) relativePath
        else "$basePath/$relativePath"
    }
}
