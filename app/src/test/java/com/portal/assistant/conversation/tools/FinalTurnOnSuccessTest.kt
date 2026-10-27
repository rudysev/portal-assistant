package com.portal.assistant.conversation.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure JVM tests for the music-command rule: a wrapped tool whose response worked makes the turn a final turn, with
 * the instruction that tells the model so; a failed or search-unsupported response leaves the conversation open,
 * untouched.
 */
class FinalTurnOnSuccessTest {

    private fun wrap(result: JSONObject) = FinalTurnOnSuccess(FixedTool(result))

    @Test fun aResponseThatWorkedMakesAFinalTurnAndTellsTheModel() {
        val output = wrap(JSONObject().put("ok", true).put("app", "Spotify")).invoke(JSONObject())

        assertEquals(
            "The conversation ends after your reply: briefly confirm, answer anything else the user asked, " +
                "and don't ask a follow-up question.",
            output.finalTurnInstruction,
        )
        assertEquals(true, output.response.getBoolean("ok"))
        assertEquals("Spotify", output.response.getString("app"))
        assertFalse(output.response.has("instruction")) // the runner adds it
    }

    @Test fun anErrorLeavesTheConversationOpenAndTheResponseUntouched() {
        val output = wrap(JSONObject().put("error", "nothing is playing")).invoke(JSONObject())

        assertNull(output.finalTurnInstruction)
        assertEquals("""{"error":"nothing is playing"}""", output.response.toString())
    }

    @Test fun anUnsupportedSearchLeavesTheConversationOpenAndTheResponseUntouched() {
        val output = wrap(JSONObject().put("ok", true).put("searchUnsupported", true)).invoke(JSONObject())

        assertNull(output.finalTurnInstruction)
        assertFalse(output.response.has("instruction"))
        assertEquals(true, output.response.getBoolean("searchUnsupported"))
    }

    @Test fun nameAndDeclarationAndArgsPassThrough() {
        val inner = FixedTool(JSONObject().put("ok", true))
        val wrapped = FinalTurnOnSuccess(inner)
        val args = JSONObject().put("action", "pause")

        wrapped.invoke(args)

        assertEquals(inner.name, wrapped.name)
        assertSame(inner.declaration, wrapped.declaration)
        assertSame(args, inner.lastArgs)
    }

    private class FixedTool(private val result: JSONObject, override val name: String = "portal.fixed") : Tool {
        override val declaration: JSONObject = JSONObject().put("name", name)
        var lastArgs: JSONObject? = null

        override fun invoke(args: JSONObject): ToolOutput {
            lastArgs = args
            return ToolOutput(result)
        }
    }
}
