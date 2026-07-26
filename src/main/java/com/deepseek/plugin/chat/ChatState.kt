package com.deepseek.plugin.chat

import com.deepseek.plugin.ui.MessageBubble
import okhttp3.sse.EventSource

/**
 * 聊天状态的有限状态机。
 *
 * 将 ChatPanel 中分散的 mutable 字段（isStreaming、currentEventSource、
 * streamBuffer、streamingBubble、firstTokenArrived、thinkingTimer）
 * 集中为不可变的 sealed class，通过 [AtomicReference] 管理，线程安全。
 *
 * 状态转换：
 *   Idle → Streaming (用户发送消息)
 *   Streaming → Idle (完成/错误/取消)
 */
sealed class ChatState {

    /** 空闲状态 — 没有进行中的请求 */
    object Idle : ChatState()

    /** 流式响应进行中 */
    data class Streaming(
        val eventSource: EventSource,
        val buffer: StringBuilder = StringBuilder(),
        val reasoningBuffer: StringBuilder = StringBuilder(),
        val bubble: MessageBubble,
        /** 请求发起的毫秒时间戳 */
        val startTime: Long = System.currentTimeMillis()
    ) : ChatState()
}
