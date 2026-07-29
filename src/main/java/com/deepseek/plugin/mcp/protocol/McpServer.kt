package com.deepseek.plugin.mcp.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.deepseek.plugin.mcp.tool.ToolRegistry

/**
 * Core MCP protocol handler. Receives JSON-RPC requests, dispatches them to the
 * appropriate MCP method handler, and returns the JSON-RPC response.
 *
 * Transport-agnostic: works with raw JSON strings.
 */
class McpServer(
    private val toolRegistry: ToolRegistry
) {
    private val initializedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Handles an incoming JSON-RPC message (as raw text).
     * Returns the response as a JSON string, or null for notifications (no response needed).
     */
    fun handleMessage(sessionId: String, raw: String): String? {
        val parsed = parseJsonRpcMessage(raw)
        return when (parsed) {
            is Either.Left -> parsed.value.toJson().toString()
            is Either.Right -> handleRequest(sessionId, parsed.value)
        }
    }

    private fun handleRequest(sessionId: String, request: JsonRpcRequest): String? {
        return when (request.method) {
            Mcp.Methods.INITIALIZE -> handleInitialize(sessionId, request)
            Mcp.Methods.INITIALIZED -> {
                initializedSessions.add(sessionId)
                null
            }
            Mcp.Methods.PING -> {
                if (request.isNotification) null
                else JsonRpcResponse(request.id!!, buildPongResult()).toJson().toString()
            }
            Mcp.Methods.TOOLS_LIST -> handleToolsList(request)
            Mcp.Methods.TOOLS_CALL -> handleToolsCall(request)
            Mcp.Methods.RESOURCES_LIST -> handleResourcesList(request)
            Mcp.Methods.RESOURCES_READ -> handleResourcesRead(request)
            Mcp.Methods.PROMPTS_LIST -> handlePromptsList(request)
            Mcp.Methods.PROMPTS_GET -> handlePromptsGet(request)
            else -> {
                if (request.isNotification) null
                else JsonRpcError(
                    request.id, JsonRpc.ErrorCodes.METHOD_NOT_FOUND,
                    "Method not found: ${request.method}"
                ).toJson().toString()
            }
        }
    }

    private fun handleInitialize(sessionId: String, request: JsonRpcRequest): String {
        initializedSessions.remove(sessionId)
        val result = buildInitializeResult(toolsChanged = true)
        return JsonRpcResponse(request.id!!, result).toJson().toString()
    }

    private fun handleToolsList(request: JsonRpcRequest): String {
        val tools = toolRegistry.getAllToolInfos()
        val result = buildToolsListResult(tools)
        return JsonRpcResponse(request.id!!, result).toJson().toString()
    }

    private fun handleToolsCall(request: JsonRpcRequest): String {
        val params = request.params
        if (params == null || !params.isJsonObject) {
            return JsonRpcError(
                request.id, JsonRpc.ErrorCodes.INVALID_PARAMS,
                "Missing params for tools/call"
            ).toJson().toString()
        }

        val obj = params.asJsonObject
        val toolName = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        if (toolName.isNullOrEmpty()) {
            return JsonRpcError(
                request.id, JsonRpc.ErrorCodes.INVALID_PARAMS,
                "Missing 'name' in tools/call params"
            ).toJson().toString()
        }

        val arguments = obj.get("arguments")
        val argMap = parseArguments(arguments)

        val result = try {
            toolRegistry.callTool(toolName, argMap)
        } catch (e: Exception) {
            McpToolResult.error("Tool execution failed: ${e.message}")
        }

        return JsonRpcResponse(request.id!!, result.toJson()).toJson().toString()
    }

    private fun parseArguments(arguments: JsonElement?): Map<String, Any?> {
        if (arguments == null || !arguments.isJsonObject) return emptyMap()
        val map = mutableMapOf<String, Any?>()
        for ((key, value) in arguments.asJsonObject.entrySet()) {
            map[key] = jsonElementToKotlin(value)
        }
        return map
    }

    private fun jsonElementToKotlin(element: JsonElement): Any? {
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val prim = element.asJsonPrimitive
            return when {
                prim.isBoolean -> prim.asBoolean
                prim.isNumber -> prim.asNumber
                else -> prim.asString
            }
        }
        if (element.isJsonArray) {
            return element.asJsonArray.map { jsonElementToKotlin(it) }
        }
        return element.toString()
    }

    private fun handleResourcesList(request: JsonRpcRequest): String {
        val result = buildResourcesListResult()
        return JsonRpcResponse(request.id!!, result).toJson().toString()
    }

    private fun handleResourcesRead(request: JsonRpcRequest): String {
        val result = JsonObject().apply {
            add("contents", com.google.gson.JsonArray())
        }
        return JsonRpcResponse(request.id!!, result).toJson().toString()
    }

    private fun handlePromptsList(request: JsonRpcRequest): String {
        val result = buildPromptsListResult()
        return JsonRpcResponse(request.id!!, result).toJson().toString()
    }

    private fun handlePromptsGet(request: JsonRpcRequest): String {
        val result = JsonObject().apply {
            add("messages", com.google.gson.JsonArray())
        }
        return JsonRpcResponse(request.id!!, result).toJson().toString()
    }

    /** Called when a session is disconnected. */
    fun onSessionClosed(sessionId: String) {
        initializedSessions.remove(sessionId)
    }

    /** Builds a tools/list_changed notification for SSE push. */
    fun buildToolsListChangedNotification(): String {
        return buildNotification(Mcp.Methods.TOOLS_LIST_CHANGED).toString()
    }
}
