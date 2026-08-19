package com.deepseek.plugin.chat

import okhttp3.Request
import okhttp3.sse.EventSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeferredEventSourceTest {
    @Test
    fun `cancel before attach cancels the real source`() {
        val deferred = DeferredEventSource()
        val actual = FakeEventSource()

        deferred.cancel()
        deferred.attach(actual)

        assertTrue(actual.cancelled)
    }

    @Test
    fun `retry attachment replaces and cancels previous source`() {
        val deferred = DeferredEventSource()
        val first = FakeEventSource("http://localhost/first")
        val second = FakeEventSource("http://localhost/second")

        deferred.attach(first)
        deferred.attach(second)

        assertTrue(first.cancelled)
        assertEquals("/second", deferred.request().url.encodedPath)
    }

    private class FakeEventSource(url: String = "http://localhost/test") : EventSource {
        var cancelled = false
        private val request = Request.Builder().url(url).build()
        override fun cancel() { cancelled = true }
        override fun request(): Request = request
    }
}
