package com.deepseek.plugin.mcp.protocol

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * JSON-RPC 2.0 message models.
 * Spec: https://www.jsonrpc.org/specification
 */

object JsonRpc {
    const val VERSION = "2.0"

    object ErrorCodes {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

/** A JSON-RPC 2.0 request or notification. */
data class JsonRpcRequest(
    val id: JsonElement?,       // null for notifications
    val method: String,
    val params: JsonElement?
) {
    val isNotification: Boolean get() = id == null || id.isJsonNull
}

/** A JSON-RPC 2.0 response (success). */
data class JsonRpcResponse(
    val id: JsonElement,
    val result: JsonElement?
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", JsonRpc.VERSION)
        add("id", id)
        if (result != null) add("result", result) else add("result", JsonNull.INSTANCE)
    }
}

/** A JSON-RPC 2.0 error response. */
data class JsonRpcError(
    val id: JsonElement?,       // may be null if the request id couldn't be parsed
    val code: Int,
    val message: String,
    val data: JsonElement? = null
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("jsonrpc", JsonRpc.VERSION)
        if (id != null) add("id", id) else add("id", JsonNull.INSTANCE)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
            if (data != null) add("data", data)
        })
    }
}

/** Parses raw JSON text into a JsonRpcRequest, or returns a JsonRpcError if parsing fails. */
fun parseJsonRpcMessage(raw: String): Either<JsonRpcError, JsonRpcRequest> {
    return try {
        val root = JsonParser.parseString(raw)
        if (!root.isJsonObject) {
            return Either.Left(JsonRpcError(
                null, JsonRpc.ErrorCodes.INVALID_REQUEST, "Request must be a JSON object"
            ))
        }
        val obj = root.asJsonObject
        val version = obj.get("jsonrpc")
        if (version == null || !version.isJsonPrimitive || version.asString != JsonRpc.VERSION) {
            return Either.Left(JsonRpcError(
                obj.get("id"), JsonRpc.ErrorCodes.INVALID_REQUEST,
                "Invalid or missing jsonrpc version"
            ))
        }
        val method = obj.get("method")
        if (method == null || !method.isJsonPrimitive) {
            return Either.Left(JsonRpcError(
                obj.get("id"), JsonRpc.ErrorCodes.INVALID_REQUEST,
                "Missing method"
            ))
        }
        val id = obj.get("id")
        val params = obj.get("params")
        Either.Right(JsonRpcRequest(id, method.asString, params))
    } catch (e: Exception) {
        Either.Left(JsonRpcError(
            null, JsonRpc.ErrorCodes.PARSE_ERROR, "Parse error: ${e.message}"
        ))
    }
}

/** Simple Either type for error handling without exceptions. */
sealed class Either<out L, out R> {
    class Left<L>(val value: L) : Either<L, Nothing>()
    class Right<R>(val value: R) : Either<Nothing, R>()
}

/** Helper to create a JSON-RPC id from a primitive value. */
fun rpcId(value: Any?): JsonElement? = when (value) {
    null -> null
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is JsonElement -> value
    else -> JsonPrimitive(value.toString())
}
