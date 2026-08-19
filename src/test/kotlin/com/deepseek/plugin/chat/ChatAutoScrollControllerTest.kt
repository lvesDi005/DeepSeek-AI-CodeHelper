package com.deepseek.plugin.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAutoScrollControllerTest {
    @Test
    fun `only user viewport changes disable bottom following`() {
        val controller = ChatAutoScrollController()

        assertTrue(controller.followsBottom)
        controller.onUserViewportChanged(value = 100, visibleAmount = 200, maximum = 1_000)

        assertFalse(controller.followsBottom)
    }

    @Test
    fun `new message and returning to bottom resume following`() {
        val controller = ChatAutoScrollController()
        controller.onUserViewportChanged(value = 100, visibleAmount = 200, maximum = 1_000)

        controller.resumeFollowing()
        assertTrue(controller.followsBottom)

        controller.onUserViewportChanged(value = 760, visibleAmount = 200, maximum = 1_000)
        assertTrue(controller.followsBottom)
    }
}
