package com.portal.assistant.conversation.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure JVM tests for the hushed flag: while the hush is held (and not kept paused), now-playing says the app is
 * paused only by the hush, so it reports the track as playing and the model describes it that way.
 */
class HushedFlagTest {

    private val hush = FakeHush(held = true)
    private val inner = FixedTool(
        JSONObject().put("playing", false).put("app", "Spotify").put("title", "One More Time"),
    )

    @Test fun nowPlayingUnderAHeldHushReportsTheTrackPlayingAndHushed() {
        val result = HushedFlag(inner, hush).invoke(JSONObject())

        assertEquals(true, result.getBoolean("hushed"))
        assertEquals(true, result.getBoolean("playing"))
        assertEquals("One More Time", result.getString("title"))
    }

    @Test fun nowPlayingWithNoHushHeldIsUnchanged() {
        hush.isHeld = false

        val result = HushedFlag(inner, hush).invoke(JSONObject())

        assertEquals(setOf("playing", "app", "title"), result.keys().asSequence().toSet())
        assertEquals(false, result.getBoolean("playing"))
    }

    @Test fun nowPlayingAfterAKeptPauseIsUnchanged() {
        hush.keepPaused()

        val result = HushedFlag(inner, hush).invoke(JSONObject())

        assertEquals(setOf("playing", "app", "title"), result.keys().asSequence().toSet())
        assertEquals(false, result.getBoolean("playing"))
    }

    @Test fun noSessionFoundUnderAHeldHushIsUnchanged() {
        val result = HushedFlag(FixedTool(JSONObject().put("playing", false)), hush).invoke(JSONObject())

        assertEquals(setOf("playing"), result.keys().asSequence().toSet())
        assertEquals(false, result.getBoolean("playing"))
    }

    @Test fun anErrorUnderAHeldHushIsUnchanged() {
        val result = HushedFlag(FixedTool(JSONObject().put("error", "no access")), hush).invoke(JSONObject())

        assertEquals(setOf("error"), result.keys().asSequence().toSet())
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
