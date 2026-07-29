package com.deepseek.plugin.mcp.transport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.deepseek.plugin.mcp.protocol.McpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Manages the local HTTP server that implements the MCP SSE transport.
 *
 * Endpoints:
 * - GET  /sse        → SSE connection (long-lived, server pushes responses)
 * - POST /messages   → Client sends JSON-RPC requests (with ?sessionId=xxx)
 * - GET  /           → Health check
 *
 * The server runs on localhost only (127.0.0.1).
 */
class McpHttpServer(
    private val mcpServer: McpServer
) {
    private val logger = logger<McpHttpServer>()

    private var server: HttpServer? = null
    private val sessions = ConcurrentHashMap<String, SseSession>()

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    /** Start the server on the given port. */
    fun start(port: Int) {
        if (isRunning) {
            logger.warn("HTTP server is already running on port ${this.port}")
            return
        }

        try {
            val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

            httpServer.createContext("/sse", SseHandler())
            httpServer.createContext("/messages", MessageHandler())
            httpServer.createContext("/", HealthCheckHandler())

            httpServer.executor = Executors.newCachedThreadPool { r ->
                Thread(r, "MCP-HTTP").apply {
                    isDaemon = true
                }
            }

            httpServer.start()
            server = httpServer
            this.port = port
            isRunning = true
            logger.info("MCP HTTP server started on http://127.0.0.1:$port")
        } catch (e: IOException) {
            logger.error("Failed to start MCP HTTP server on port $port", e)
            throw e
        }
    }

    /** Stop the server and close all SSE sessions. */
    fun stop() {
        if (!isRunning) return

        sessions.values.forEach { it.close() }
        sessions.clear()

        server?.stop(0)
        server = null
        isRunning = false
        logger.info("MCP HTTP server stopped")
    }

    /** Get the SSE endpoint URL. */
    fun getSseUrl(): String = "http://127.0.0.1:$port/sse"

    /** Get the number of active SSE sessions. */
    fun sessionCount(): Int = sessions.size

    // --- Handlers ---

    /** Handles GET /sse — establishes a long-lived SSE connection. */
    private inner class SseHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }

            val session = SseSession()
            sessions[session.sessionId] = session

            val headers = exchange.responseHeaders
            headers["Content-Type"] = listOf("text/event-stream")
            headers["Cache-Control"] = listOf("no-cache")
            headers["Connection"] = listOf("keep-alive")
            headers["Access-Control-Allow-Origin"] = listOf("*")

            exchange.sendResponseHeaders(200, 0)

            val os = exchange.responseBody
            session.attach(os, "/messages")

            try {
                while (!session.isClosed) {
                    Thread.sleep(100)
                }
            } catch (e: InterruptedException) {
                // Normal shutdown
            } catch (e: IOException) {
                // Client disconnected
            } finally {
                sessions.remove(session.sessionId)
                mcpServer.onSessionClosed(session.sessionId)
                session.close()
                exchange.close()
            }
        }
    }

    /** Handles POST /messages?sessionId=xxx — receives JSON-RPC requests. */
    private inner class MessageHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "POST") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }

            val query = exchange.requestURI.query ?: ""
            val sessionId = extractQueryParam(query, "sessionId")

            if (sessionId == null) {
                sendJsonResponse(exchange, 400, """{"error":"Missing sessionId"}""")
                return
            }

            val session = sessions[sessionId]
            if (session == null) {
                sendJsonResponse(exchange, 404, """{"error":"Session not found"}""")
                return
            }

            val body = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }

            ApplicationManager.getApplication().executeOnPooledThread {
                val response = mcpServer.handleMessage(sessionId, body)
                if (response != null) {
                    session.sendMessage(response)
                }
            }

            sendJsonResponse(exchange, 202, """{"status":"accepted"}""")
        }
    }

    /** Handles GET / — simple health check. */
    private inner class HealthCheckHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            if (exchange.requestMethod != "GET") {
                exchange.sendResponseHeaders(405, -1)
                exchange.close()
                return
            }

            val response = """{"status":"ok","server":"DeepSeek MCP Bridge","sessions":${sessions.size}}"""
            sendJsonResponse(exchange, 200, response)
        }
    }

    private fun sendJsonResponse(exchange: HttpExchange, statusCode: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("application/json")
        exchange.responseHeaders["Access-Control-Allow-Origin"] = listOf("*")
        exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun extractQueryParam(query: String, key: String): String? {
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == key }
            ?.get(1)
    }
}
