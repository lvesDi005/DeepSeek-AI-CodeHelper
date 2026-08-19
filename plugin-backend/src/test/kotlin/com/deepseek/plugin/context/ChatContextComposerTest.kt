package com.deepseek.plugin.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ChatContextComposerTest {

    @Test
    fun `isSourceExt recognizes common extensions`() {
        assertTrue(ChatContextComposer.isSourceExt("kt"))
        assertTrue(ChatContextComposer.isSourceExt("java"))
        assertTrue(ChatContextComposer.isSourceExt("py"))
        assertTrue(ChatContextComposer.isSourceExt("ts"))
        assertTrue(ChatContextComposer.isSourceExt("xml"))
        assertFalse(ChatContextComposer.isSourceExt("exe"))
        assertFalse(ChatContextComposer.isSourceExt("png"))
        assertFalse(ChatContextComposer.isSourceExt(null))
    }

    @Test
    fun `isCodeQuery detects CamelCase class names`() {
        assertTrue(ChatContextComposer.isCodeQuery("What does UserController do?"))
        assertTrue(ChatContextComposer.isCodeQuery("Explain the findUserById method"))
    }

    @Test
    fun `isCodeQuery detects code keywords`() {
        assertTrue(ChatContextComposer.isCodeQuery("How to create a repository?"))
        assertTrue(ChatContextComposer.isCodeQuery("What is the service interface?"))
        assertTrue(ChatContextComposer.isCodeQuery("这个接口怎么实现？"))
    }

    @Test
    fun `isCodeQuery returns false for plain text`() {
        assertFalse(ChatContextComposer.isCodeQuery("What is the weather today?"))
        assertFalse(ChatContextComposer.isCodeQuery("Explain how to write a README"))
    }

    @Test
    fun `extractSearchKeywords finds CamelCase names`() {
        val keywords = ChatContextComposer.extractSearchKeywords("Where is UserController defined?")
        assertTrue(keywords.contains("UserController"))
    }

    @Test
    fun `extractSearchKeywords finds quoted strings`() {
        val keywords = ChatContextComposer.extractSearchKeywords("Search for "getUserById" in the codebase")
        assertTrue(keywords.contains("getUserById"))
    }

    @Test
    fun `extractSearchKeywords finds method patterns`() {
        val keywords = ChatContextComposer.extractSearchKeywords("I need to find the getConnectionPool method")
        assertTrue(keywords.contains("getConnectionPool"))
    }

    @Test
    fun `extractSearchKeywords returns empty for unrelated text`() {
        val keywords = ChatContextComposer.extractSearchKeywords("a b c d e")
        assertTrue(keywords.isEmpty())
    }

    @Test
    fun `extractSearchKeywords deduplicates`() {
        val keywords = ChatContextComposer.extractSearchKeywords("UserController and UserController again")
        assertEquals(1, keywords.count { it == "UserController" })
    }

    @Test
    fun `truncateCodeBlocks truncates long code blocks`() {
        val input = """
            ```kotlin
            |line 1
            |line 2
            |line 3
            |line 4
            |line 5
            |line 6
            |line 7
            |line 8
            |line 9
            |line 10
            |line 11
            |line 12
            |line 13
            |line 14
            |line 15
            |line 16
            |line 17
            |line 18
            |line 19
            |line 20
            |line 21
            |line 22
            ```
        """.trimMargin()
        val result = ChatContextComposer.truncateCodeBlocks(input, maxLines = 5)
        assertTrue("truncated block contains marker") { result.contains("......") }
        assertTrue("truncated block is shorter") { result.lines().size < input.lines().size }
    }

    @Test
    fun `truncateCodeBlocks preserves short code blocks`() {
        val input = """
            ```kotlin
            |line 1
            |line 2
            |line 3
            ```
        """.trimMargin()
        val result = ChatContextComposer.truncateCodeBlocks(input, maxLines = 5)
        assertEquals(input, result)
    }
}