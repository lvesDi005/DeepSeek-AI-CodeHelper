package com.deepseek.plugin.chat

import com.deepseek.plugin.api.ChatMessage
import com.deepseek.plugin.api.Usage
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatMessageUsageTest {
    private val gson = Gson()

    @Test
    fun `usage survives session json round trip`() {
        val message = ChatMessage("assistant", "answer", usage = Usage(80, 20, 100))

        val restored = gson.fromJson(gson.toJson(message), ChatMessage::class.java)

        assertEquals(100, restored.usage?.totalTokens)
    }

    @Test
    fun `old session without usage remains compatible`() {
        val restored = gson.fromJson(
            """{"role":"assistant","content":"old answer"}""",
            ChatMessage::class.java
        )

        assertNull(restored.usage)
    }
}
