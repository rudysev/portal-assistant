package com.portal.assistant.conversation.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for pause under hush: a pause or stop while the hush is held keeps the hush's pause instead of
 * calling the media session.
 */
class PauseUnderHushTest {

    private val hush = FakeHush(held = true)
    private val inner = FixedTool(JSONObject().put("action", "pause").put("app", "Spotify"))

    @Test fun aPauseUnderAHeldHushKeepsThePauseWithoutTheMediaSession() {
        val result = PauseUnderHush(inner, hush).invoke(JSONObject().put("action", "pause"))

        assertEquals(1, hush.keepPausedCalls)
        assertEquals(0, inner.calls)
        assertEquals("""{"action":"pause"}""", result.toString())
    }

    @Test fun aStopUnderAHeldHushIsAPause() {
        val result = PauseUnderHush(inner, hush).invoke(JSONObject().put("action", " Stop "))

        assertEquals(1, hush.keepPausedCalls)
        assertEquals(0, inner.calls)
        assertEquals("""{"action":"pause"}""", result.toString())
    }

    @Test fun aPauseWithNoHushHeldGoesToTheMediaSession() {
        hush.isHeld = false
        val args = JSONObject().put("action", "pause")

        val result = PauseUnderHush(inner, hush).invoke(args)

        assertEquals(0, hush.keepPausedCalls)
        assertEquals(1, inner.calls)
        assertSame(args, inner.lastArgs)
        assertSame(inner.result, result)
    }

    @Test fun aPauseWhoseKeptPauseIsDeniedGoesToTheMediaSession() {
        val deniedHush = FakeHush(held = true, keepPausedGranted = false)
        val args = JSONObject().put("action", "pause")

        val result = PauseUnderHush(inner, deniedHush).invoke(args)

        assertEquals(1, deniedHush.keepPausedCalls)
        assertEquals(1, inner.calls)
        assertSame(args, inner.lastArgs)
        assertSame(inner.result, result)
    }

    @Test fun everyOtherActionUnderAHeldHushPassesThrough() {
        listOf("next", "previous", "play", "resume", "skip", "back").forEach { action ->
            PauseUnderHush(inner, hush).invoke(JSONObject().put("action", action))
        }

        assertEquals(0, hush.keepPausedCalls)
        assertEquals(6, inner.calls)
    }

    @Test fun aKeptPauseInsideTheFinalTurnWrapperRequestsTheFinalTurn() {
        var finalTurns = 0
        val tool = FinalTurnOnSuccess(PauseUnderHush(inner, hush)) { finalTurns++ }

        val result = tool.invoke(JSONObject().put("action", "pause"))

        assertEquals(1, finalTurns)
        assertEquals(1, hush.keepPausedCalls)
        assertEquals("pause", result.getString("action"))
        assertTrue(result.has("instruction"))
    }

    @Test fun nameAndDeclarationPassThrough() {
        val wrapped = PauseUnderHush(inner, hush)

        assertEquals(inner.name, wrapped.name)
        assertSame(inner.declaration, wrapped.declaration)
    }

    private class FixedTool(val result: JSONObject) : Tool {
        override val name = "portal.media_control"
        override val declaration: JSONObject = JSONObject().put("name", name)
        var calls = 0
        var lastArgs: JSONObject? = null

        override fun invoke(args: JSONObject): JSONObject {
            calls++
            lastArgs = args
            return result
        }
    }
}
