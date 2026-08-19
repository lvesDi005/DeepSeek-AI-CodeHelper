package com.deepseek.plugin.chat

import com.deepseek.plugin.api.ChatContentType
import com.deepseek.plugin.api.ChatMessage

data class QaRequestBudget(
    val maxContextTokens: Int,
    val reservedOutputTokens: Int
) {
    val maxInputTokens: Int = (maxContextTokens - reservedOutputTokens).coerceAtLeast(1_024)
}

data class QaComposedRequest(
    val messages: List<ChatMessage>,
    val estimatedInputTokens: Int,
    val historyMessagesDropped: Int,
    val transientContextTruncated: Boolean
)

/** Builds a bounded request without mutating the clean, persisted conversation history. */
object QaRequestComposer {
    private const val CONTEXT_HEADER = "## Context for this turn only\n"
    private const val TRUNCATED_MARKER = "\n[Context truncated to fit the model input budget]"

    fun compose(
        systemPrompt: String,
        history: List<ChatMessage>,
        transientContext: String,
        budget: QaRequestBudget,
        currentUserParts: List<com.deepseek.plugin.api.ChatContentPart> = emptyList()
    ): QaComposedRequest {
        val systemMessage = ChatMessage("system", systemPrompt)
        val inputBudget = (budget.maxInputTokens - estimateTokens(systemMessage)).coerceAtLeast(512)
        if (history.isEmpty()) {
            return QaComposedRequest(listOf(systemMessage), estimateTokens(systemMessage), 0, false)
        }

        val currentIndex = history.indexOfLast { it.role == "user" }.takeIf { it >= 0 }
            ?: history.lastIndex
        val current = history[currentIndex]
        val baseCurrent = current.copy(parts = currentUserParts.ifEmpty { current.parts.orEmpty() })
        val currentBaseTokens = estimateTokens(baseCurrent)
        val contextTokenBudget = (inputBudget - currentBaseTokens).coerceAtLeast(0)
        val boundedContext = truncateToTokenBudget(transientContext.trim(), contextTokenBudget)
        val currentForRequest = if (boundedContext.text.isBlank()) {
            baseCurrent
        } else {
            baseCurrent.copy(content = "$CONTEXT_HEADER${boundedContext.text}\n\n## User question\n${baseCurrent.content}")
        }

        val selected = mutableListOf<ChatMessage>()
        var used = estimateTokens(currentForRequest)
        var index = currentIndex - 1
        while (index >= 0) {
            val turnStart = if (history[index].role == "assistant" && index > 0 && history[index - 1].role == "user") {
                index - 1
            } else index
            val turn = history.subList(turnStart, index + 1)
            val cost = turn.sumOf(::estimateTokens)
            if (used + cost > inputBudget) break
            selected.addAll(0, turn)
            used += cost
            index = turnStart - 1
        }
        selected.add(currentForRequest)

        val messages = listOf(systemMessage) + selected
        return QaComposedRequest(
            messages = messages,
            estimatedInputTokens = messages.sumOf(::estimateTokens),
            historyMessagesDropped = history.size - selected.size,
            transientContextTruncated = boundedContext.truncated
        )
    }

    internal fun estimateTokens(message: ChatMessage): Int {
        val textTokens = estimateTokens(message.content) + 4
        val imageTokens = message.parts.orEmpty().count { it.type == ChatContentType.IMAGE } * 1_200
        val partTextTokens = message.parts.orEmpty()
            .filter { it.type == ChatContentType.TEXT }
            .sumOf { estimateTokens(it.text.orEmpty()) }
        return textTokens + imageTokens + partTextTokens
    }

    internal fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var nonAscii = 0
        text.forEach { if (it.code < 128) ascii++ else nonAscii++ }
        return ((ascii + 3) / 4) + ((nonAscii + 1) / 2)
    }

    private data class BoundedText(val text: String, val truncated: Boolean)

    private fun truncateToTokenBudget(text: String, tokenBudget: Int): BoundedText {
        if (text.isBlank() || tokenBudget <= 0) return BoundedText("", text.isNotBlank())
        if (estimateTokens(text) <= tokenBudget) return BoundedText(text, false)

        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (estimateTokens(text.substring(0, mid) + TRUNCATED_MARKER) <= tokenBudget) low = mid
            else high = mid - 1
        }
        return BoundedText(text.take(low).trimEnd() + TRUNCATED_MARKER, true)
    }
}
