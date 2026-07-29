package com.deepseek.plugin.mcp.client

import com.deepseek.plugin.mcp.protocol.McpToolInfo
import com.deepseek.plugin.mcp.protocol.McpToolResult
import com.deepseek.plugin.mcp.tool.McpToolDefinition

/**
 * Adapts an external MCP tool (from another server) into an [McpToolDefinition]
 * that can be used internally by the plugin.
 *
 * The tool name is prefixed with the server name to avoid conflicts:
 * e.g. "stripe_get_balance" instead of just "get_balance".
 */
class ExternalMcpTool(
    serverName: String,
    private val toolInfo: McpToolInfo,
    private val connection: McpClientConnection
) : McpToolDefinition(
    name = "${serverName}_${toolInfo.name}",
    description = "[$serverName] ${toolInfo.description}",
    inputSchema = toolInfo.inputSchema
) {
    /** Original tool name on the remote server. */
    val originalName: String = toolInfo.name

    /** The server this tool belongs to. */
    val server: String = serverName

    override fun execute(arguments: Map<String, Any?>): McpToolResult {
        return connection.callTool(originalName, arguments)
    }

    /** Create a display string for the tools list. */
    fun toDisplayString(): String = "$server / $originalName — ${toolInfo.description}"
}
