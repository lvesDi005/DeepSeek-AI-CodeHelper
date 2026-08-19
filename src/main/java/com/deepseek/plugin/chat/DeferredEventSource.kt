package com.deepseek.plugin.chat

import okhttp3.Request
import okhttp3.sse.EventSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** EventSource placeholder that can be installed in UI state before the HTTP call starts. */
internal class DeferredEventSource : EventSource {
    private val delegate = AtomicReference<EventSource?>()
    private val cancelled = AtomicBoolean(false)
    private val fallbackRequest = Request.Builder().url("http://localhost/pending-chat-request").build()

    fun attach(eventSource: EventSource) {
        val previous = delegate.getAndSet(eventSource)
        if (cancelled.get()) eventSource.cancel()
        else previous?.cancel()
    }

    override fun cancel() {
        cancelled.set(true)
        delegate.get()?.cancel()
    }

    override fun request(): Request = delegate.get()?.request() ?: fallbackRequest
}
