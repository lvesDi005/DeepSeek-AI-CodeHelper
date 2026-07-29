package com.deepseek.plugin.mcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.deepseek.plugin.mcp.protocol.McpToolResult
import com.deepseek.plugin.mcp.tool.InputSchema
import com.deepseek.plugin.mcp.tool.McpToolDefinition
import com.deepseek.plugin.mcp.tool.McpToolProvider

/**
 * Built-in project tools: get_project_info, get_open_files, get_active_editor_content.
 */
class ProjectToolProvider : McpToolProvider {
    override fun getTools(): List<McpToolDefinition> = listOf(
        GetProjectInfoTool(),
        GetOpenFilesTool(),
        GetActiveEditorContentTool()
    )
}

class GetProjectInfoTool : McpToolDefinition(
    name = "get_project_info",
    description = "Get information about the open project(s): name, base path, project SDK, and project type.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "project" to InputSchema.PropertyDef("string", "Project name (optional, uses first open project if not specified)")
        )
    )
) {
    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val projects = ProjectManager.getInstance().openProjects
        if (projects.isEmpty()) {
            return McpToolResult.error("No project open")
        }

        val targetProject = if (arguments.containsKey("project")) {
            projects.firstOrNull { it.name == arguments["project"] }
                ?: return McpToolResult.error("Project not found: ${arguments["project"]}")
        } else {
            projects.first()
        }

        var result: McpToolResult = McpToolResult.error("Failed to get project info")

        ApplicationManager.getApplication().runReadAction {
            val sdk = ProjectRootManager.getInstance(targetProject).projectSdk
            val info = buildString {
                appendLine("Project: ${targetProject.name}")
                appendLine("Base path: ${targetProject.basePath ?: "(none)"}")
                appendLine("Project SDK: ${sdk?.name ?: "(not configured)"}")
                appendLine("Project file path: ${targetProject.projectFilePath ?: "(none)"}")
                appendLine("Open projects: ${projects.map { it.name }}")
            }
            result = McpToolResult.text(info.trim())
        }
        return result
    }
}

class GetOpenFilesTool : McpToolDefinition(
    name = "get_open_files",
    description = "List all files currently open in the editor tabs.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "project" to InputSchema.PropertyDef("string", "Project name (optional)")
        )
    )
) {
    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        var result: McpToolResult = McpToolResult.error("Failed to get open files")

        ApplicationManager.getApplication().runReadAction {
            val editorManager = FileEditorManager.getInstance(project)
            val openFiles = editorManager.openFiles
            if (openFiles.isEmpty()) {
                result = McpToolResult.text("No files open in editor")
            } else {
                val fileList = openFiles.joinToString("\n") { file ->
                    val name = file.name
                    val path = file.path
                    val isActive = file == editorManager.selectedFiles.firstOrNull()
                    val marker = if (isActive) " → " else "   "
                    "$marker$name  ($path)"
                }
                result = McpToolResult.text("Open files (${openFiles.size}):\n$fileList")
            }
        }
        return result
    }
}

class GetActiveEditorContentTool : McpToolDefinition(
    name = "get_active_editor_content",
    description = "Get the content of the currently active editor, including cursor position and selected text.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "project" to InputSchema.PropertyDef("string", "Project name (optional)")
        )
    )
) {
    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        var result: McpToolResult = McpToolResult.error("No active editor")

        ApplicationManager.getApplication().runReadAction {
            val editorManager = FileEditorManager.getInstance(project)
            val editor = editorManager.selectedTextEditor
            if (editor == null) {
                result = McpToolResult.error("No active text editor")
            } else {
                val document = editor.document
                val caret = editor.caretModel
                val selection = editor.selectionModel
                val selectedText = if (selection.hasSelection()) selection.selectedText else "(no selection)"

                val info = buildString {
                    appendLine("File: ${editorManager.selectedFiles.firstOrNull()?.name ?: "?"}")
                    appendLine("Total lines: ${document.lineCount}")
                    appendLine("Total chars: ${document.textLength}")
                    appendLine("Cursor: line ${caret.logicalPosition.line + 1}, column ${caret.logicalPosition.column + 1}")
                    appendLine("Selection: $selectedText")
                    appendLine("--- Content ---")
                    appendLine(document.text)
                }
                result = McpToolResult.text(info.trim())
            }
        }
        return result
    }
}
