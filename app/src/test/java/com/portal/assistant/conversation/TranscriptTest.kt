package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Exhaustive tests for the pure [Transcript] accumulator (turn boundaries, appends, interleaving). */
class TranscriptTest {

    // Compare by role+text (ids are global-monotonic and not meaningful to assert).
    private fun Transcript.pairs() = turns.map { it.role to it.text }

    @Test fun emptyByDefault() {
        assertEquals(emptyList<Pair<Role, String>>(), Transcript().pairs())
    }

    @Test fun firstUserDeltaCreatesOneTurn() {
        val t = Transcript().appendUser("what's ")
        assertEquals(listOf(Role.USER to "what's "), t.pairs())
    }

    @Test fun consecutiveUserDeltasGrowSameTurn() {
        val t = Transcript().appendUser("what's ").appendUser("the weather")
        assertEquals(listOf(Role.USER to "what's the weather"), t.pairs())
    }

    @Test fun emptyDeltaIsIgnored() {
        val t = Transcript().appendUser("hi").appendUser("").appendModel("")
        assertEquals(listOf(Role.USER to "hi"), t.pairs())
    }

    @Test fun modelDeltaStartsSeparateTurn() {
        val t = Transcript().appendUser("hi").appendModel("hello ").appendModel("there")
        assertEquals(listOf(Role.USER to "hi", Role.MODEL to "hello there"), t.pairs())
    }

    @Test fun beginUserTurnStartsAFreshTurnEvenForSameRole() {
        // Two separate user utterances with no model between them (e.g. re-listen) must not merge.
        val t = Transcript().appendUser("first").beginUserTurn().appendUser("second")
        assertEquals(listOf(Role.USER to "first", Role.USER to "second"), t.pairs())
    }

    @Test fun beginModelTurnStartsAFreshModelTurn() {
        val t = Transcript().appendModel("a").beginModelTurn().appendModel("b")
        assertEquals(listOf(Role.MODEL to "a", Role.MODEL to "b"), t.pairs())
    }

    @Test fun boundaryArmsFreshModelBubbleEvenWhenModelDeltaIsFirstAfterIt() {
        // Mirrors the engine's StartForwarding (beginUserTurn().beginModelTurn()): the prior answer's bubble
        // is closed at the turn boundary, so the next answer's first model delta starts a FRESH bubble
        // instead of extending the previous answer — even though appendModel is the first call after the
        // boundary (the dropped-first-words fix: works regardless of text-vs-audio arrival order).
        val t = Transcript()
            .appendModel("first answer")
            .beginUserTurn().beginModelTurn() // turn boundary
            .appendModel("second answer beginning")
        assertEquals(
            listOf(Role.MODEL to "first answer", Role.MODEL to "second answer beginning"),
            t.pairs(),
        )
    }

    @Test fun fullMultiTurnConversation() {
        val t = Transcript()
            .beginUserTurn().appendUser("weather?")
            .beginModelTurn().appendModel("It's ").appendModel("sunny.")
            .beginUserTurn().appendUser("tomorrow?")
            .beginModelTurn().appendModel("Rain.")
        assertEquals(
            listOf(
                Role.USER to "weather?",
                Role.MODEL to "It's sunny.",
                Role.USER to "tomorrow?",
                Role.MODEL to "Rain.",
            ),
            t.pairs(),
        )
    }

    @Test fun modelMarkdownIsStrippedAcrossDeltas() {
        // The model streams markdown; bold markers split across deltas ("**Es" + "presso:**") still strip.
        val t = Transcript().appendUser("drinks?").appendModel("**Es").appendModel("presso:** the best")
        assertEquals(listOf(Role.USER to "drinks?", Role.MODEL to "Espresso: the best"), t.pairs())
    }

    @Test fun modelLeadingListMarkerDefersBubbleUntilRealText() {
        // A leading "1. " strips to empty, so no empty model bubble appears until real text arrives.
        val afterMarker = Transcript().appendModel("1. ")
        assertEquals(emptyList<Pair<Role, String>>(), afterMarker.pairs())
        val grown = afterMarker.appendModel("Espresso\n2. Latte")
        assertEquals(listOf(Role.MODEL to "Espresso\nLatte"), grown.pairs())
    }

    @Test fun userTextIsNotStripped() {
        // Only model text is markdown; a user turn keeps its characters verbatim.
        val t = Transcript().appendUser("what is 2 * 3?")
        assertEquals(listOf(Role.USER to "what is 2 * 3?"), t.pairs())
    }

    @Test fun turnIdIsStableAcrossAppends() {
        val first = Transcript().appendModel("Let me ")
        val grown = first.appendModel("tell you")
        assertEquals(first.turns.last().id, grown.turns.last().id)
        assertEquals("Let me tell you", grown.turns.last().text)
    }

    @Test fun isImmutable() {
        val base = Transcript().appendUser("hi")
        base.appendUser(" there") // discarded
        assertEquals(listOf(Role.USER to "hi"), base.pairs())
    }
}
