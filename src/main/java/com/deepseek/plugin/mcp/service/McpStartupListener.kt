package com.deepseek.plugin.mcp.service

import com.deepseek.plugin.mcp.client.ExternalMcpManager
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.logger

/**
 * Triggers MCP initialization when the IDE application has started.
 *
 * Starts:
 * - MCP Server (SSE/HTTP, exposes IDEA tools to external clients)
 * - External MCP Client connections (connects to configured external servers)
 */
class McpStartupListener : AppLifecycleListener {

    private val logger = logger<McpStartupListener>()

    override fun appStarted() {
        try {
            McpServerService.getInstance().onAppStarted()
        } catch (e: Exception) {
            logger.error("MCP Bridge startup failed", e)
        }

        try {
            ExternalMcpManager.getInstance().connectAll()
        } catch (e: Exception) {
            logger.error("External MCP client startup failed", e)
        }
    }
}
