package com.portal.assistant.conversation.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure JVM tests for the music-command rule: a wrapped tool whose result worked makes the turn a final turn and tells
 * the model so; a failed or search-unsupported result leaves the conversation open, untouched.
 */
class FinalTurnOnSuccessTest {

    private var finalTurns = 0

    private fun wrap(result: JSONObject) = FinalTurnOnSuccess(FixedTool(result)) { finalTurns++ }

    @Test fun aResultThatWorkedRequestsTheFinalTurnAndTellsTheModel() {
        val result = wrap(JSONObject().put("ok", true).put("app", "Spotify")).invoke(JSONObject())

        assertEquals(1, finalTurns)
        assertEquals(
            "The conversation ends after your reply: briefly confirm, answer anything else the user asked, " +
                "and don't ask a follow-up question.",
            result.getString("instruction"),
        )
        assertEquals(true, result.getBoolean("ok"))
        assertEquals("Spotify", result.getString("app"))
    }

    @Test fun anErrorLeavesTheConversationOpenAndTheResultUntouched() {
        val result = wrap(JSONObject().put("error", "nothing is playing")).invoke(JSONObject())

        assertEquals(0, finalTurns)
        assertEquals("""{"error":"nothing is playing"}""", result.toString())
    }

    @Test fun anUnsupportedSearchLeavesTheConversationOpenAndTheResultUntouched() {
        val result = wrap(JSONObject().put("ok", true).put("searchUnsupported", true)).invoke(JSONObject())

        assertEquals(0, finalTurns)
        assertFalse(result.has("instruction"))
        assertEquals(true, result.getBoolean("searchUnsupported"))
    }

    @Test fun nameAndDeclarationAndArgsPassThrough() {
        val inner = FixedTool(JSONObject().put("ok", true))
        val wrapped = FinalTurnOnSuccess(inner) {}
        val args = JSONObject().put("action", "pause")

        wrapped.invoke(args)

        assertEquals(inner.name, wrapped.name)
        assertSame(inner.declaration, wrapped.declaration)
        assertSame(args, inner.lastArgs)
    }

    @Test fun nothingIsRequestedUntilTheToolRuns() {
        wrap(JSONObject().put("ok", true))

        assertEquals(0, finalTurns)
    }

    private class FixedTool(private val result: JSONObject, override val name: String = "portal.fixed") : Tool {
        override val declaration: JSONObject = JSONObject().put("name", name)
        var lastArgs: JSONObject? = null

        override fun invoke(args: JSONObject): JSONObject {
            lastArgs = args
            return result
        }
    }
}
