package com.deepseek.plugin.mcp.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * MCP (Model Context Protocol) message models and constants.
 * Spec: https://modelcontextprotocol.io/specification
 */

object Mcp {
    const val PROTOCOL_VERSION = "2024-11-05"
    const val SERVER_NAME = "DeepSeek MCP Bridge"
    const val SERVER_VERSION = "1.0.0"

    object Methods {
        const val INITIALIZE = "initialize"
        const val INITIALIZED = "notifications/initialized"
        const val PING = "ping"
        const val TOOLS_LIST = "tools/list"
        const val TOOLS_CALL = "tools/call"
        const val RESOURCES_LIST = "resources/list"
        const val RESOURCES_READ = "resources/read"
        const val PROMPTS_LIST = "prompts/list"
        const val PROMPTS_GET = "prompts/get"
        const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"
    }
}

/** MCP tool definition returned by tools/list. */
data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", inputSchema)
    }
}

/** MCP tool call result. */
data class McpToolResult(
    val content: List<McpContent>,
    val isError: Boolean = false
) {
    fun toJson(): JsonObject = JsonObject().apply {
        val arr = JsonArray()
        content.forEach { arr.add(it.toJson()) }
        add("content", arr)
        addProperty("isError", isError)
    }

    companion object {
        fun text(text: String, isError: Boolean = false): McpToolResult =
            McpToolResult(listOf(McpContent.text(text)), isError)

        fun error(message: String): McpToolResult =
            McpToolResult(listOf(McpContent.text(message)), isError = true)
    }
}

/** MCP content block. */
sealed class McpContent {
    abstract fun toJson(): JsonObject

    data class Text(val text: String) : McpContent() {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
    }

    data class Image(val data: String, val mimeType: String) : McpContent() {
        override fun toJson() = JsonObject().apply {
            addProperty("type", "image")
            addProperty("data", data)
            addProperty("mimeType", mimeType)
        }
    }

    companion object {
        fun text(text: String) = Text(text)
    }
}

/** Builds the MCP initialize response. */
fun buildInitializeResult(toolsChanged: Boolean = true): JsonObject = JsonObject().apply {
    addProperty("protocolVersion", Mcp.PROTOCOL_VERSION)
    add("capabilities", JsonObject().apply {
        add("tools", JsonObject().apply {
            addProperty("listChanged", toolsChanged)
        })
        add("resources", JsonObject().apply {
            addProperty("listChanged", true)
        })
        add("prompts", JsonObject().apply {
            addProperty("listChanged", true)
        })
    })
    add("serverInfo", JsonObject().apply {
        addProperty("name", Mcp.SERVER_NAME)
        addProperty("version", Mcp.SERVER_VERSION)
    })
}

/** Builds a tools/list response. */
fun buildToolsListResult(tools: List<McpToolInfo>): JsonObject = JsonObject().apply {
    val arr = JsonArray()
    tools.forEach { arr.add(it.toJson()) }
    add("tools", arr)
}

/** Builds a ping response (empty result). */
fun buildPongResult(): JsonObject = JsonObject()

/** Builds a resources/list response (empty for now). */
fun buildResourcesListResult(): JsonObject = JsonObject().apply {
    add("resources", JsonArray())
}

/** Builds a prompts/list response (empty for now). */
fun buildPromptsListResult(): JsonObject = JsonObject().apply {
    add("prompts", JsonArray())
}

/** JSON-RPC notification helper. */
fun buildNotification(method: String, params: JsonObject? = null): JsonObject = JsonObject().apply {
    addProperty("jsonrpc", JsonRpc.VERSION)
    addProperty("method", method)
    if (params != null) add("params", params)
}
