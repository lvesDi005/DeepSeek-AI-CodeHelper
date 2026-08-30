package com.deepseek.plugin.chat

/** Tracks user scroll intent independently from layout-driven scrollbar changes. */
internal class ChatAutoScrollController(private val bottomThreshold: Int = 8) {
    var followsBottom: Boolean = true
        private set

    fun resumeFollowing() {
        followsBottom = true
    }

    /** 用户明确离开底部（如向上滚动）— 同步调用，立即暂停跟随。 */
    fun onUserScrollAway() {
        followsBottom = false
    }

    fun onUserViewportChanged(value: Int, visibleAmount: Int, maximum: Int) {
        followsBottom = value + visibleAmount >= maximum - bottomThreshold
    }
}
