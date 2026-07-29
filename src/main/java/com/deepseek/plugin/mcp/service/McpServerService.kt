package com.deepseek.plugin.mcp.service

import com.deepseek.plugin.mcp.client.ExternalMcpManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.deepseek.plugin.mcp.protocol.McpServer
import com.deepseek.plugin.mcp.tool.ToolRegistry
import com.deepseek.plugin.mcp.transport.McpHttpServer
import com.deepseek.plugin.settings.DeepSeekSettings

/**
 * Application-level service that manages the MCP Server lifecycle.
 *
 * Startup is triggered via invokeLater in init to avoid internal API
 * usage (AppLifecycleListener is marked internal).
 */
class McpServerService {

    private val logger = logger<McpServerService>()

    val toolRegistry: ToolRegistry = ToolRegistry()
    private lateinit var mcpServer: McpServer
    private var httpServer: McpHttpServer? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    init {
        // Schedule startup after IDE is fully initialized
        ApplicationManager.getApplication().invokeLater {
            onAppStarted()
            try {
                ExternalMcpManager.getInstance().connectAll()
            } catch (e: Exception) {
                logger.error("External MCP client startup failed", e)
            }
        }
    }

    companion object {
        fun getInstance(): McpServerService =
            ApplicationManager.getApplication().getService(McpServerService::class.java)
    }

    /** Called on plugin startup. */
    fun onAppStarted() {
        toolRegistry.refreshFromExtensions()
        mcpServer = McpServer(toolRegistry)

        val settings = DeepSeekSettings.instance
        if (settings.mcpEnabled && settings.mcpAutoStart) {
            startServer(settings.mcpPort)
        }
    }

    /** Start the MCP HTTP server on the configured port. */
    fun startServer(port: Int): Boolean {
        if (isRunning) {
            logger.warn("MCP Bridge server is already running")
            return false
        }

        return try {
            if (!::mcpServer.isInitialized) {
                mcpServer = McpServer(toolRegistry)
            }
            httpServer = McpHttpServer(mcpServer)
            httpServer!!.start(port)
            isRunning = true
            notify("MCP Server started", "Listening on http://127.0.0.1:$port/sse", NotificationType.INFORMATION)
            true
        } catch (e: Exception) {
            logger.error("Failed to start MCP server", e)
            notify("MCP Server failed to start", e.message ?: "Unknown error", NotificationType.ERROR)
            false
        }
    }

    /** Stop the MCP HTTP server. */
    fun stopServer() {
        if (!isRunning) return
        httpServer?.stop()
        httpServer = null
        isRunning = false
        notify("MCP Server stopped", "Server is no longer listening", NotificationType.INFORMATION)
    }

    /** Restart the server with current settings. */
    fun restartServer(): Boolean {
        val settings = DeepSeekSettings.instance
        stopServer()
        return if (settings.mcpEnabled) {
            startServer(settings.mcpPort)
        } else {
            true
        }
    }

    /** Get the SSE URL if the server is running. */
    fun getSseUrl(): String? = if (isRunning) httpServer?.getSseUrl() else null

    /** Get the number of active client sessions. */
    fun sessionCount(): Int = httpServer?.sessionCount() ?: 0

    /** Get all registered tools. */
    fun getTools() = toolRegistry.getAllTools()

    /** Refresh tools from Extension Points. */
    fun refreshTools() {
        toolRegistry.refreshFromExtensions()
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("DeepSeek AI CodeHelper")
                .createNotification(title, content, type)
                .notify(null)
        }
    }
}
