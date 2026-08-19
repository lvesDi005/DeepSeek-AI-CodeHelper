package com.deepseek.plugin.chat

/** Tracks user scroll intent independently from layout-driven scrollbar changes. */
internal class ChatAutoScrollController(private val bottomThreshold: Int = 50) {
    var followsBottom: Boolean = true
        private set

    fun resumeFollowing() {
        followsBottom = true
    }

    fun onUserViewportChanged(value: Int, visibleAmount: Int, maximum: Int) {
        followsBottom = value + visibleAmount >= maximum - bottomThreshold
    }
}
