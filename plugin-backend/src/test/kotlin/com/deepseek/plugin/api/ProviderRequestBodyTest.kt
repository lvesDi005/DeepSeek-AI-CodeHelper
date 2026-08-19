package com.deepseek.plugin.api

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderRequestBodyTest {
    private val client = DeepSeekApiClient()
    private val image = ChatContentPart(
        type = ChatContentType.IMAGE,
        dataUri = "data:image/png;base64,AAAA",
        mediaType = "image/png",
        name = "page.png"
    )

    @Test
    fun `anthropic body uses native image content block`() {
        val body = client.buildAnthropicBody(
            model = "claude-test",
            messages = listOf(
                ChatMessage("system", "system rules"),
                ChatMessage("user", "inspect", parts = listOf(image))
            ),
            temperature = 0.7,
            maxTokens = 2_000,
            stream = true
        )
        val json = JsonParser.parseString(body).asJsonObject
        val message = json.getAsJsonArray("messages")[0].asJsonObject
        val content = message.getAsJsonArray("content")

        assertEquals("system rules", json.get("system").asString)
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("image", content[1].asJsonObject.get("type").asString)
        assertEquals("image/png", content[1].asJsonObject.getAsJsonObject("source").get("media_type").asString)
    }

    @Test
    fun `codex body uses input image reasoning effort and omits temperature`() {
        val body = client.buildCodexResponsesBody(
            model = "gpt-test-codex",
            messages = listOf(
                ChatMessage("system", "system rules"),
                ChatMessage("user", "inspect", parts = listOf(image))
            ),
            temperature = 0.2,
            maxTokens = 2_000,
            stream = true,
            reasoningEffort = "high"
        )
        val json = JsonParser.parseString(body).asJsonObject
        val content = json.getAsJsonArray("input")[0].asJsonObject.getAsJsonArray("content")

        assertEquals("high", json.getAsJsonObject("reasoning").get("effort").asString)
        assertFalse(json.has("temperature"))
        assertEquals("input_text", content[0].asJsonObject.get("type").asString)
        assertEquals("input_image", content[1].asJsonObject.get("type").asString)
        assertTrue(content[1].asJsonObject.get("image_url").asString.startsWith("data:image/png;base64,"))
    }
}
