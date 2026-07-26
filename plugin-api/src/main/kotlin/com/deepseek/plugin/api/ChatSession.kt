package com.deepseek.plugin.api

/**
 * 聊天会话 — 表示一次聊天会话的元数据和消息列表。
 */
data class ChatSession(
    val name: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var totalTokens: Int = 0,
    var lastActiveTime: Long = System.currentTimeMillis()
)
