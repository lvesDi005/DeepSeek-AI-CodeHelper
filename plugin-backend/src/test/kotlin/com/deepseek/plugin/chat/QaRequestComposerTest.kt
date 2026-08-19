package com.deepseek.plugin.chat

import com.deepseek.plugin.api.ChatContentPart
import com.deepseek.plugin.api.ChatContentType
import com.deepseek.plugin.api.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QaRequestComposerTest {
    @Test
    fun `transient context is injected without mutating persisted history`() {
        val history = listOf(ChatMessage("user", "How does this work?"))

        val request = QaRequestComposer.compose(
            systemPrompt = "system",
            history = history,
            transientContext = "private per-turn source context",
            budget = QaRequestBudget(8_000, 1_000)
        )

        assertEquals("How does this work?", history.single().content)
        assertTrue(request.messages.last().content.contains("private per-turn source context"))
        assertTrue(request.messages.last().content.endsWith("How does this work?"))
    }

    @Test
    fun `budget drops old complete turns and preserves current question`() {
        val huge = "x".repeat(8_000)
        val history = listOf(
            ChatMessage("user", huge),
            ChatMessage("assistant", "old answer"),
            ChatMessage("user", "current question")
        )

        val request = QaRequestComposer.compose(
            systemPrompt = "system",
            history = history,
            transientContext = "",
            budget = QaRequestBudget(1_500, 300)
        )

        assertEquals(2, request.historyMessagesDropped)
        assertEquals("current question", request.messages.last().content)
        assertFalse(request.messages.any { it.content == "old answer" })
    }

    @Test
    fun `context is truncated and native image parts remain request only`() {
        val image = ChatContentPart(
            type = ChatContentType.IMAGE,
            dataUri = "data:image/png;base64,AAAA",
            mediaType = "image/png",
            name = "sample.png"
        )
        val history = listOf(ChatMessage("user", "inspect image"))

        val request = QaRequestComposer.compose(
            systemPrompt = "system",
            history = history,
            transientContext = "上下文".repeat(4_000),
            budget = QaRequestBudget(2_500, 500),
            currentUserParts = listOf(image)
        )

        assertTrue(request.transientContextTruncated)
        assertEquals(listOf(image), request.messages.last().parts)
        assertTrue(history.single().parts.isNullOrEmpty())
    }
}
