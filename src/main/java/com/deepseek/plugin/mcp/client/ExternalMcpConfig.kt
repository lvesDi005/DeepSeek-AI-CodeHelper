package com.deepseek.plugin.mcp.client

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Transport type for an external MCP server. */
enum class TransportType { SSE, STDIO }

/**
 * Configuration for a single external MCP server connection.
 */
data class ExternalMcpConfig(
    /** Unique name used as tool-name prefix, e.g. "stripe". */
    var name: String = "",
    /** Transport type. */
    var transportType: String = "SSE", // "SSE" or "STDIO"
    /** For SSE: URL like "https://mcp.stripe.com/sse". For STDIO: command path. */
    var url: String = "",
    /** STDIO arguments (space-separated string for simplicity). */
    var args: String = "",
    /** STDIO environment variables (KEY=VALUE, one per line). */
    var env: String = "",
    /** Whether this server is enabled. */
    var enabled: Boolean = true,
    /** Auto-connect on IDE startup. */
    var autoStart: Boolean = true
) {
    /** Parse args string to list. */
    fun parseArgs(): List<String> =
        if (args.isBlank()) emptyList() else args.split(" ").filter { it.isNotBlank() }

    /** Parse env string to map. */
    fun parseEnv(): Map<String, String> {
        if (env.isBlank()) return emptyMap()
        return env.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx) to line.substring(idx + 1)
            }
    }
}

/** Transport type used in UI. */
enum class TransportDisplay(val label: String, val key: String) {
    SSE("HTTP/SSE", "SSE"),
    STDIO("STDIO", "STDIO")
}

/**
 * Persistent storage for external MCP server configurations.
 */
@State(
    name = "ExternalMcpServers",
    storages = [Storage("external-mcp-servers.xml")]
)
class ExternalMcpStore : PersistentStateComponent<ExternalMcpStore> {

    var servers: MutableList<ExternalMcpConfig> = mutableListOf()

    override fun getState(): ExternalMcpStore = this

    override fun loadState(state: ExternalMcpStore) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): ExternalMcpStore =
            ApplicationManager.getApplication().getService(ExternalMcpStore::class.java)
    }
}
