package com.deepseek.plugin.mcp.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.deepseek.plugin.access.ChainedFileAccess
import com.deepseek.plugin.access.FileAccessService
import com.deepseek.plugin.mcp.protocol.McpToolResult
import com.deepseek.plugin.mcp.tool.InputSchema
import com.deepseek.plugin.mcp.tool.McpToolDefinition
import com.deepseek.plugin.mcp.tool.McpToolProvider

/**
 * Built-in search tools: search_in_project, find_symbol.
 *
 * search_in_project delegates to [FileAccessService] for unified
 * file search behavior shared with Agent mode and Q&A scan mode.
 */
class SearchToolProvider : McpToolProvider {

    private val fileAccess: FileAccessService = ChainedFileAccess()

    override fun getTools(): List<McpToolDefinition> = listOf(
        SearchInProjectTool(fileAccess),
        FindSymbolTool()
    )
}

class SearchInProjectTool(
    private val fileAccess: FileAccessService
) : McpToolDefinition(
    name = "search_in_project",
    description = "Search for a text string in all project files. Returns matching file paths, line numbers, and line content.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "query" to InputSchema.PropertyDef("string", "Text to search for"),
            "max_results" to InputSchema.PropertyDef("number", "Maximum number of results to return (default: 50)"),
            "project" to InputSchema.PropertyDef("string", "Project name (optional)")
        ),
        required = listOf("query")
    )
) {
    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val query = arguments["query"] as? String
            ?: return McpToolResult.error("Missing 'query' parameter")
        val maxResults = (arguments["max_results"] as? Number)?.toInt() ?: 50

        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        val results = fileAccess.searchInProject(query, maxResults, project)

        return if (results.isEmpty()) {
            McpToolResult.text("No matches found for '$query'")
        } else {
            val header = "Found ${results.size} match(es) for '$query':\n\n"
            val text = results.joinToString("\n") { "${it.filePath}:${it.lineNumber}: ${it.lineContent}" }
            McpToolResult.text(header + text)
        }
    }
}

class FindSymbolTool : McpToolDefinition(
    name = "find_symbol",
    description = "Find declarations (classes, functions, methods) by name in the project using PSI symbol search.",
    inputSchema = InputSchema.objectSchema(
        properties = mapOf(
            "name" to InputSchema.PropertyDef("string", "Symbol name to search for (e.g., class name, function name)"),
            "project" to InputSchema.PropertyDef("string", "Project name (optional)")
        ),
        required = listOf("name")
    )
) {
    private val logger = logger<FindSymbolTool>()

    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        val name = arguments["name"] as? String
            ?: return McpToolResult.error("Missing 'name' parameter")

        val project = ProjectUtils.getProject(arguments["project"] as? String)
            ?: return McpToolResult.error("No project open")

        val results = mutableListOf<String>()

        ApplicationManager.getApplication().runReadAction {
            try {
                val cache = com.intellij.psi.search.PsiShortNamesCache.getInstance(project)
                val maxResults = 50

                val classes = cache.getClassesByName(name, com.intellij.psi.search.ProjectScope.getContentScope(project))
                for (cls in classes.take(maxResults)) {
                    val file = cls.containingFile?.virtualFile?.path ?: "?"
                    results.add("[class] ${cls.qualifiedName ?: cls.name}  ($file)")
                }

                val methods = cache.getMethodsByName(name, com.intellij.psi.search.ProjectScope.getContentScope(project))
                if (methods.isNotEmpty()) {
                    for (method in methods.take(maxResults)) {
                        val file = method.containingFile?.virtualFile?.path ?: "?"
                        val className = method.containingClass?.qualifiedName ?: "?"
                        results.add("[method] $className.${method.name}()  ($file)")
                    }
                }

                try {
                    val fields = cache.getFieldsByName(name, com.intellij.psi.search.ProjectScope.getContentScope(project))
                    for (field in fields.take(maxResults)) {
                        val file = field.containingFile?.virtualFile?.path ?: "?"
                        val className = field.containingClass?.qualifiedName ?: "?"
                        results.add("[field] $className.$name  ($file)")
                    }
                } catch (e: NoSuchMethodError) {
                    // getFieldsByName might not be available in all versions
                }
            } catch (e: Exception) {
                logger.error("Symbol search failed", e)
            }
        }

        return if (results.isEmpty()) {
            McpToolResult.text("No symbols found matching '$name'")
        } else {
            McpToolResult.text("Found ${results.size} symbol(s) matching '$name':\n\n" + results.joinToString("\n"))
        }
    }
}
