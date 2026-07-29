package com.deepseek.plugin.mcp.tool

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.deepseek.plugin.mcp.protocol.McpToolInfo
import com.deepseek.plugin.mcp.protocol.McpToolResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of all MCP Tools available in the plugin.
 *
 * Tools come from two sources:
 * 1. Extension Point for other plugins to register tools
 * 2. Programmatic registration via [registerTool]
 *
 * The registry is an application-level singleton, managed by McpServerService.
 */
class ToolRegistry {

    private val logger = logger<ToolRegistry>()
    private val tools = ConcurrentHashMap<String, McpToolDefinition>()

    companion object {
        val EP_NAME = ExtensionPointName<McpToolProvider>("com.deepseek.plugin.mcpToolProvider")
    }

    /**
     * Scans all Extension Point implementations and registers their tools.
     */
    fun refreshFromExtensions() {
        val collected = mutableListOf<McpToolDefinition>()
        EP_NAME.extensionList.forEach { provider ->
            try {
                val providerTools = provider.getTools()
                collected.addAll(providerTools)
                logger.info("Loaded ${providerTools.size} tools from ${provider.javaClass.name}")
            } catch (e: Exception) {
                logger.error("Failed to load tools from ${provider.javaClass.name}", e)
            }
        }
        tools.clear()
        collected.forEach { tools[it.name] = it }
        logger.info("ToolRegistry refreshed: ${tools.size} tools registered")
    }

    /** Programmatically register a tool. */
    fun registerTool(tool: McpToolDefinition) {
        tools[tool.name] = tool
    }

    /** Unregister a tool by name. */
    fun unregisterTool(name: String) {
        tools.remove(name)
    }

    /** Get all tool infos for tools/list response. */
    fun getAllToolInfos(): List<McpToolInfo> = tools.values.map { it.toInfo() }

    /** Get all tool definitions. */
    fun getAllTools(): List<McpToolDefinition> = tools.values.toList()

    /**
     * Execute a tool by name with the given arguments.
     */
    fun callTool(name: String, arguments: Map<String, Any?>): McpToolResult {
        val tool = tools[name]
            ?: return McpToolResult.error("Tool not found: $name")

        return try {
            tool.execute(arguments)
        } catch (e: Exception) {
            logger.error("Tool '$name' execution failed", e)
            McpToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    /** Check if a tool exists by name. */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /** Number of registered tools. */
    fun toolCount(): Int = tools.size
}
