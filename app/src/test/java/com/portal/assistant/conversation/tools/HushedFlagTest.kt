package com.portal.assistant.conversation.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure JVM tests for the hushed flag: while the hush is held (and not kept paused), now-playing says the app is
 * paused only by the hush, so the model describes the track as playing.
 */
class HushedFlagTest {

    private val hush = FakeHush(held = true)
    private val inner = FixedTool(JSONObject().put("playing", false).put("title", "One More Time"))

    @Test fun nowPlayingUnderAHeldHushIsFlaggedHushed() {
        val result = HushedFlag(inner, hush).invoke(JSONObject())

        assertEquals(true, result.getBoolean("hushed"))
        assertEquals(false, result.getBoolean("playing"))
        assertEquals("One More Time", result.getString("title"))
    }

    @Test fun nowPlayingWithNoHushHeldIsUnchanged() {
        hush.isHeld = false

        val result = HushedFlag(inner, hush).invoke(JSONObject())

        assertEquals(setOf("playing", "title"), result.keys().asSequence().toSet())
    }

    @Test fun nowPlayingAfterAKeptPauseIsUnchanged() {
        hush.keepPaused()

        val result = HushedFlag(inner, hush).invoke(JSONObject())

        assertEquals(setOf("playing", "title"), result.keys().asSequence().toSet())
    }

    @Test fun nameAndDeclarationPassThrough() {
        val wrapped = HushedFlag(inner, hush)

        assertEquals(inner.name, wrapped.name)
        assertSame(inner.declaration, wrapped.declaration)
    }

    private class FixedTool(val result: JSONObject) : Tool {
        override val name = "portal.now_playing"
        override val declaration: JSONObject = JSONObject().put("name", name)

        override fun invoke(args: JSONObject): JSONObject = result
    }
}
