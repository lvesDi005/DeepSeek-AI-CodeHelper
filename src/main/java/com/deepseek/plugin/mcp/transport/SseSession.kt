package com.deepseek.plugin.mcp.transport

import com.intellij.openapi.diagnostic.logger
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Represents a single SSE (Server-Sent Events) connection from an AI client.
 *
 * The SSE connection stays open: the server pushes JSON-RPC responses
 * and notifications through this session's output stream.
 */
class SseSession(
    val sessionId: String = UUID.randomUUID().toString()
) {
    private val logger = logger<SseSession>()

    @Volatile
    private var outputStream: OutputStream? = null

    @Volatile
    private var closed: Boolean = false

    private val messageQueue = ConcurrentLinkedQueue<String>()

    /** Attach the output stream and send the endpoint event. */
    @Synchronized
    fun attach(outputStream: OutputStream, messageEndpointPath: String) {
        this.outputStream = outputStream
        // Send the endpoint event first (MCP requirement)
        sendRaw("event: endpoint\ndata: $messageEndpointPath?sessionId=$sessionId\n\n")
        // Flush any queued messages
        flushQueue()
        logger.info("SSE session $sessionId attached")
    }

    /** Send a JSON-RPC message to the client via SSE. */
    @Synchronized
    fun sendMessage(json: String) {
        if (closed) return
        val sseData = formatSseMessage(json)
        if (outputStream == null) {
            messageQueue.add(sseData)
        } else {
            sendRaw(sseData)
        }
    }

    private fun formatSseMessage(json: String): String {
        val lines = json.split("\n")
        val sb = StringBuilder()
        sb.append("event: message\n")
        for (line in lines) {
            sb.append("data: ").append(line).append("\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun flushQueue() {
        while (messageQueue.isNotEmpty()) {
            val msg = messageQueue.poll()
            sendRaw(msg)
        }
    }

    private fun sendRaw(data: String) {
        try {
            val os = outputStream ?: return
            os.write(data.toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (e: Exception) {
            logger.warn("Failed to send SSE data to session $sessionId: ${e.message}")
            closed = true
        }
    }

    /** Close the session and release the output stream. */
    @Synchronized
    fun close() {
        closed = true
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (e: Exception) {
            logger.debug("Error closing SSE session $sessionId: ${e.message}")
        }
        outputStream = null
        logger.info("SSE session $sessionId closed")
    }

    val isClosed: Boolean get() = closed
}
