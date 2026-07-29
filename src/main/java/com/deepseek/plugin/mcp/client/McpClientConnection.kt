package com.deepseek.plugin.mcp.client

import com.deepseek.plugin.mcp.protocol.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client connection to a single external MCP server.
 *
 * Supports both SSE (remote HTTP server) and STDIO (local subprocess) transports.
 * Reuses protocol data models from [JsonRpc] and [McpModels].
 */
class McpClientConnection(
    private val config: ExternalMcpConfig
) {
    private val logger = logger<McpClientConnection>()
    private val gson = Gson()

    @Volatile
    var isConnected: Boolean = false
        private set

    @Volatile
    var statusMessage: String = "Disconnected"
        private set

    /** Tools available from this server (cached after listTools). */
    @Volatile
    private var cachedTools: List<McpToolInfo> = emptyList()

    // SSE state
    private var sseSessionId: String? = null
    private var sseEndpoint: String? = null
    private var sseThread: Thread? = null
    @Volatile
    private var sseRunning = false

    // STDIO state
    private var stdioProcess: Process? = null
    private var stdioWriter: BufferedWriter? = null
    private var stdioReader: BufferedReader? = null

    // Pending responses: maps request id → latch + result
    private val pendingResponses = ConcurrentHashMap<String, PendingResponse>()

    private var nextId = 1
    private val idLock = Any()

    private data class PendingResponse(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var result: String? = null,
        @Volatile var error: String? = null
    )

    // ── Connection ──

    /** Connect to the MCP server. Blocks until initialize handshake completes. */
    fun connect(): Boolean {
        if (isConnected) return true
        return try {
            when (config.transportType) {
                "SSE" -> connectSse()
                "STDIO" -> connectStdio()
                else -> {
                    statusMessage = "Unknown transport: ${config.transportType}"
                    false
                }
            }
        } catch (e: Exception) {
            statusMessage = "Connection failed: ${e.message}"
            logger.warn("MCP connect failed for ${config.name}", e)
            false
        }
    }

    private fun connectSse(): Boolean {
        val baseUrl = config.url.trimEnd('/')
        val sseUrl = if (baseUrl.endsWith("/sse")) baseUrl else "$baseUrl/sse"

        // Start SSE reader thread
        sseRunning = true
        val latch = CountDownLatch(1)

        sseThread = Thread({
            try {
                val url = URL(sseUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.connectTimeout = 5000
                conn.readTimeout = 0 // indefinite
                conn.doInput = true

                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))

                while (sseRunning) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event: endpoint") -> {
                            val dataLine = reader.readLine() ?: continue
                            if (dataLine.startsWith("data: ")) {
                                sseEndpoint = dataLine.removePrefix("data: ").trim()
                                sseEndpoint?.let { ep ->
                                    val queryIdx = ep.indexOf('?')
                                    if (queryIdx >= 0) {
                                        val query = ep.substring(queryIdx + 1)
                                        sseSessionId = query.split("&")
                                            .map { it.split("=", limit = 2) }
                                            .firstOrNull { it.size == 2 && it[0] == "sessionId" }
                                            ?.get(1)
                                    }
                                }
                                latch.countDown()
                            }
                        }
                        line.startsWith("event: message") -> {
                            val dataLine = reader.readLine() ?: continue
                            if (dataLine.startsWith("data: ")) {
                                val json = dataLine.removePrefix("data: ").trim()
                                handleResponse(json)
                            }
                        }
                        line.startsWith("data: ") && sseSessionId != null -> {
                            val json = line.removePrefix("data: ").trim()
                            handleResponse(json)
                        }
                    }
                }
            } catch (e: Exception) {
                if (sseRunning) {
                    logger.warn("SSE connection closed for ${config.name}", e)
                }
            } finally {
                sseRunning = false
                isConnected = false
                statusMessage = "Disconnected"
            }
        }, "MCP-SSE-${config.name}").apply { isDaemon = true; start() }

        // Wait for endpoint event (up to 10 seconds)
        if (!latch.await(10, TimeUnit.SECONDS)) {
            statusMessage = "SSE handshake timeout"
            sseRunning = false
            return false
        }

        // Send initialize request
        val initResult = sendRequest("initialize", buildInitializeParams())
        if (initResult == null) {
            statusMessage = "Initialize failed"
            sseRunning = false
            return false
        }

        // Send initialized notification
        sendNotification("notifications/initialized", null)

        isConnected = true
        statusMessage = "Connected (SSE)"
        logger.info("MCP SSE connected: ${config.name} at $sseUrl")
        return true
    }

    private fun buildInitializeParams(): JsonObject = JsonObject().apply {
        addProperty("protocolVersion", "2024-11-05")
        add("capabilities", JsonObject())
        add("clientInfo", JsonObject().apply {
            addProperty("name", "DeepSeek-CodeHelper")
            addProperty("version", "2.7.0")
        })
    }

    private fun connectStdio(): Boolean {
        val cmd = config.url
        val argsList = config.parseArgs()

        val pb = ProcessBuilder(cmd, *argsList.toTypedArray())
        pb.environment().putAll(config.parseEnv())
        pb.redirectErrorStream(false)

        val process = pb.start()
        stdioProcess = process
        stdioWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)
        stdioReader = process.inputStream.bufferedReader(Charsets.UTF_8)

        // Start stderr reader thread (to avoid blocking)
        Thread({
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { errReader ->
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        logger.debug("MCP STDERR [${config.name}]: $line")
                    }
                }
            } catch (_: Exception) { }
        }, "MCP-STDERR-${config.name}").apply { isDaemon = true; start() }

        // Start stdout reader thread
        Thread({
            try {
                var line: String?
                while (stdioReader?.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    handleResponse(l.trim())
                }
            } catch (_: Exception) { }
        }, "MCP-STDIO-${config.name}").apply { isDaemon = true; start() }

        // Send initialize
        val initResult = sendRequest("initialize", buildInitializeParams())
        if (initResult == null) {
            process.destroy()
            statusMessage = "Initialize failed"
            return false
        }

        sendNotification("notifications/initialized", null)

        isConnected = true
        statusMessage = "Connected (STDIO)"
        logger.info("MCP STDIO connected: ${config.name} ($cmd)")
        return true
    }

    /** Disconnect from the MCP server. */
    fun disconnect() {
        isConnected = false
        statusMessage = "Disconnected"
        sseRunning = false
        sseThread?.interrupt()
        sseThread = null
        stdioProcess?.destroy()
        stdioProcess = null
        stdioWriter = null
        stdioReader = null
        sseSessionId = null
        sseEndpoint = null
        cachedTools = emptyList()
        // Cancel all pending requests
        pendingResponses.forEach { (_, pr) ->
            pr.error = "Disconnected"
            pr.latch.countDown()
        }
        pendingResponses.clear()
    }

    // ── Tool operations ──

    /** Fetch available tools from the server. Caches the result. */
    fun listTools(): List<McpToolInfo> {
        if (!isConnected) return emptyList()
        if (cachedTools.isNotEmpty()) return cachedTools

        val result = sendRequest("tools/list", null) ?: return emptyList()
        return try {
            val obj = JsonParser.parseString(result).asJsonObject
            val toolsArr = obj.getAsJsonArray("tools")
            val tools = mutableListOf<McpToolInfo>()
            for (el in toolsArr) {
                val toolObj = el.asJsonObject
                tools.add(McpToolInfo(
                    name = toolObj.get("name").asString,
                    description = toolObj.get("description")?.asString ?: "",
                    inputSchema = toolObj.getAsJsonObject("inputSchema") ?: JsonObject()
                ))
            }
            cachedTools = tools
            tools
        } catch (e: Exception) {
            logger.warn("Failed to parse tools list for ${config.name}", e)
            emptyList()
        }
    }

    /** Call a tool on the external server. */
    fun callTool(toolName: String, arguments: Map<String, Any?>): McpToolResult {
        if (!isConnected) return McpToolResult.error("Not connected to ${config.name}")

        val params = JsonObject().apply {
            addProperty("name", toolName)
            val argsObj = JsonObject()
            arguments.forEach { (k, v) ->
                when (v) {
                    is String -> argsObj.addProperty(k, v)
                    is Number -> argsObj.addProperty(k, v)
                    is Boolean -> argsObj.addProperty(k, v)
                    else -> argsObj.addProperty(k, v?.toString() ?: "")
                }
            }
            add("arguments", argsObj)
        }

        val result = sendRequest("tools/call", params) ?: return McpToolResult.error("No response from ${config.name}")
        return try {
            val obj = JsonParser.parseString(result).asJsonObject
            val contentArr = obj.getAsJsonArray("content")
            val isError = obj.get("isError")?.asBoolean ?: false
            val content = mutableListOf<McpContent>()
            for (el in contentArr) {
                val c = el.asJsonObject
                val type = c.get("type")?.asString ?: "text"
                if (type == "text") {
                    content.add(McpContent.text(c.get("text")?.asString ?: ""))
                }
            }
            McpToolResult(content, isError)
        } catch (e: Exception) {
            logger.warn("Failed to parse tool result for ${config.name}/$toolName", e)
            McpToolResult.error("Failed to parse response: ${e.message}")
        }
    }

    /** Refresh cached tools (call again on next listTools). */
    fun invalidateCache() { cachedTools = emptyList() }

    // ── JSON-RPC helpers ──

    private fun sendRequest(method: String, params: JsonObject?): String? {
        val id = synchronized(idLock) { nextId++.toString() }
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val jsonStr = gson.toJson(request)

        val pr = PendingResponse()
        pendingResponses[id] = pr

        try {
            when (config.transportType) {
                "SSE" -> sendViaSse(jsonStr)
                "STDIO" -> sendViaStdio(jsonStr)
            }
        } catch (e: Exception) {
            pendingResponses.remove(id)
            statusMessage = "Send failed: ${e.message}"
            return null
        }

        // Wait for response (up to 60 seconds for tool calls, 10 for others)
        val timeout = if (method == "tools/call") 120L else 10L
        if (!pr.latch.await(timeout, TimeUnit.SECONDS)) {
            pendingResponses.remove(id)
            statusMessage = "Response timeout for $method"
            return null
        }

        pendingResponses.remove(id)
        return pr.error?.let { null } ?: pr.result
    }

    private fun sendNotification(method: String, params: JsonObject?) {
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        val jsonStr = gson.toJson(request)
        try {
            when (config.transportType) {
                "SSE" -> sendViaSse(jsonStr)
                "STDIO" -> sendViaStdio(jsonStr)
            }
        } catch (e: Exception) {
            logger.warn("Failed to send notification $method", e)
        }
    }

    private fun sendViaSse(json: String) {
        val endpoint = sseEndpoint ?: throw IOException("No SSE endpoint")
        val fullUrl = if (endpoint.startsWith("http")) endpoint
        else "${config.url.trimEnd('/')}$endpoint"

        val url = URL(fullUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 5000
        conn.readTimeout = 30000

        conn.outputStream.use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
            os.flush()
        }

        // Read response (202 Accepted or similar)
        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errorBody = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
            throw IOException("HTTP $responseCode: $errorBody")
        }
        // For SSE transport, the actual response comes via SSE stream
    }

    private fun sendViaStdio(json: String) {
        val writer = stdioWriter ?: throw IOException("STDIO not connected")
        synchronized(this) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }
    }

    private fun handleResponse(json: String) {
        try {
            val obj = JsonParser.parseString(json).asJsonObject
            val id = obj.get("id")?.toString()?.trim('"')

            if (id != null && pendingResponses.containsKey(id)) {
                val pr = pendingResponses[id] ?: return
                if (obj.has("error")) {
                    pr.error = obj.getAsJsonObject("error").get("message")?.asString ?: "Unknown error"
                } else {
                    val result = obj.get("result")
                    pr.result = result?.toString()
                }
                pr.latch.countDown()
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse SSE/STDIO response for ${config.name}", e)
        }
    }
}
