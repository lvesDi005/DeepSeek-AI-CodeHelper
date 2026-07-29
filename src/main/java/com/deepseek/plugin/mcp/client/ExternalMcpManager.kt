package com.deepseek.plugin.mcp.client

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.deepseek.plugin.mcp.protocol.McpToolResult

/**
 * Manages connections to external MCP servers.
 *
 * Application-level singleton. Provides:
 * - Connect/disconnect all configured servers
 * - Aggregate available tools from all servers
 * - Route tool calls to the correct server
 */
class ExternalMcpManager {

    private val logger = logger<ExternalMcpManager>()
    private val connections = mutableMapOf<String, McpClientConnection>()
    private val toolMap = mutableMapOf<String, ExternalMcpTool>()

    companion object {
        fun getInstance(): ExternalMcpManager =
            ApplicationManager.getApplication().getService(ExternalMcpManager::class.java)
    }

    /** Connect all enabled servers. Called on IDE startup. */
    fun connectAll() {
        val store = ExternalMcpStore.getInstance()
        for (config in store.servers) {
            if (config.enabled && config.autoStart && config.name.isNotBlank()) {
                connect(config)
            }
        }
    }

    /** Connect a single server by config. */
    fun connect(config: ExternalMcpConfig): Boolean {
        // Disconnect existing if any
        disconnect(config.name)

        val connection = McpClientConnection(config)
        val ok = connection.connect()
        if (ok) {
            connections[config.name] = connection
            refreshTools(config.name, connection)
            logger.info("External MCP connected: ${config.name}")
        } else {
            logger.warn("External MCP connection failed: ${config.name} - ${connection.statusMessage}")
        }
        return ok
    }

    /** Disconnect a server by name. */
    fun disconnect(name: String) {
        connections.remove(name)?.disconnect()
        // Remove all tools from this server
        toolMap.entries.removeAll { it.value.server == name }
    }

    /** Disconnect all servers. */
    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        toolMap.clear()
    }

    /** Reconnect a specific server. */
    fun reconnect(config: ExternalMcpConfig): Boolean {
        disconnect(config.name)
        return connect(config)
    }

    /** Refresh tools from a server. */
    private fun refreshTools(serverName: String, connection: McpClientConnection) {
        // Remove old tools from this server
        toolMap.entries.removeAll { it.value.server == serverName }

        val tools = connection.listTools()
        for (toolInfo in tools) {
            val adapted = ExternalMcpTool(serverName, toolInfo, connection)
            toolMap[adapted.name] = adapted
        }
        logger.info("Loaded ${tools.size} tools from $serverName")
    }

    /** Get all available external tools as McpToolDefinitions. */
    fun getAllTools(): List<ExternalMcpTool> = toolMap.values.toList()

    /** Get all external tools formatted for LLM tool definitions. */
    fun getToolDefinitionsForLlm(): String {
        if (toolMap.isEmpty()) return ""
        return buildString {
            appendLine("\nExternal tools (call via <tool> tag):")
            for (tool in toolMap.values.sortedBy { it.name }) {
                appendLine("  - ${tool.name}: ${tool.description}")
                appendLine("    Schema: ${tool.inputSchema}")
            }
        }
    }

    /** Find a tool by its prefixed name (e.g. "stripe_get_balance"). */
    fun findTool(name: String): ExternalMcpTool? = toolMap[name]

    /** Check if a tool with the given name exists. */
    fun hasTool(name: String): Boolean = toolMap.containsKey(name)

    /** Call a tool by its full prefixed name. */
    fun callTool(name: String, arguments: Map<String, Any?>): McpToolResult {
        val tool = toolMap[name]
            ?: return McpToolResult.error("External tool not found: $name")
        return tool.execute(arguments)
    }

    /** Get connection status for display. */
    fun getConnectionStatus(name: String): String {
        val conn = connections[name]
        return conn?.statusMessage ?: "Not configured"
    }

    /** Check if a server is connected. */
    fun isConnected(name: String): Boolean =
        connections[name]?.isConnected == true

    /** Re-scan tools from all connected servers. */
    fun refreshAllTools() {
        for ((name, conn) in connections) {
            if (conn.isConnected) {
                conn.invalidateCache()
                refreshTools(name, conn)
            }
        }
    }
}
