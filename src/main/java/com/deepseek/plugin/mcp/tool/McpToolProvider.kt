package com.deepseek.plugin.mcp.tool

import com.google.gson.JsonObject
import com.deepseek.plugin.mcp.protocol.McpContent
import com.deepseek.plugin.mcp.protocol.McpToolInfo
import com.deepseek.plugin.mcp.protocol.McpToolResult

/**
 * Extension Point interface. Other plugins implement this to register
 * custom MCP Tools.
 *
 * Register in plugin.xml:
 * ```xml
 * <extensions defaultExtensionNs="com.deepseek.plugin">
 *     <mcpToolProvider implementation="com.example.MyToolProvider"/>
 * </extensions>
 * ```
 */
interface McpToolProvider {
    /** Returns the list of tools this provider offers. */
    fun getTools(): List<McpToolDefinition>
}

/**
 * Definition of a single MCP Tool. Each tool has a name, description,
 * an input schema (JSON Schema), and an execute function.
 */
abstract class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject
) {
    abstract fun execute(arguments: Map<String, Any?>): McpToolResult

    fun toInfo(): McpToolInfo = McpToolInfo(name, description, inputSchema)
}

/**
 * Convenience base class for simple text-returning tools.
 */
abstract class TextTool(
    name: String,
    description: String,
    inputSchema: JsonObject
) : McpToolDefinition(name, description, inputSchema) {

    abstract fun executeText(arguments: Map<String, Any?>): String

    final override fun execute(arguments: Map<String, Any?>): McpToolResult {
        return try {
            val text = executeText(arguments)
            McpToolResult.text(text)
        } catch (e: Exception) {
            McpToolResult.error("Error: ${e.message}")
        }
    }
}

/**
 * Helper to build a simple JSON Schema for tool input.
 */
object InputSchema {
    fun objectSchema(
        properties: Map<String, PropertyDef>,
        required: List<String> = emptyList()
    ): JsonObject {
        val props = JsonObject()
        for ((key, prop) in properties) {
            props.add(key, prop.toJson())
        }
        return JsonObject().apply {
            addProperty("type", "object")
            add("properties", props)
            if (required.isNotEmpty()) {
                val arr = com.google.gson.JsonArray()
                required.forEach { arr.add(it) }
                add("required", arr)
            }
        }
    }

    data class PropertyDef(
        val type: String,
        val description: String
    ) {
        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("type", type)
            addProperty("description", description)
        }
    }
}
