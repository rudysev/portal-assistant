package com.portal.assistant.conversation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reducer's final turn: once a result marked [ToolResult.finalTurn] is sent to the model, the next turn end —
 * normal or stalled — ends the conversation instead of re-listening, even in multi-turn and across an interrupt. A
 * marked result that isn't sent ends nothing. The rest of the reducer is [ConversationStateTest]'s.
 */
class FinalTurnStateTest {

    private fun fc(id: String) = FunctionCall(id, "portal.test", JSONObject())
    private fun final(id: String) = ToolResult(id, "portal.test", JSONObject(), finalTurn = true)
    private fun plain(id: String) = ToolResult(id, "portal.test", JSONObject())

    private fun reduceAll(events: List<Event>): Decision {
        var state = ConversationState()
        val actions = mutableListOf<Action>()
        for (e in events) {
            val d = reduce(state, e, multiTurn = true)
            state = d.state
            actions += d.actions
        }
        return Decision(state, actions)
    }

    @Test fun finalTurnEndsAtTurnEndInsteadOfReListening() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(final("c1"))),
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
        )
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.End), d.actions.takeLast(2))
        assertEquals(1, d.actions.count { it == Action.StartForwarding }) // only the opening's
    }

    @Test fun stalledFinalTurnEndsInsteadOfReListening() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(final("c1"))),
                Event.StallTimeout,
            ),
        )
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.End), d.actions.takeLast(2))
    }

    @Test fun finalTurnSurvivesInterrupt() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(final("c1"))),
                Event.Interrupted,
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
        )
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.End), d.actions.takeLast(2))
    }

    @Test fun aMarkedResultOvertakenByABargeInEndsNothing() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.Interrupted, // the user speaks again before the result reaches the model
                Event.ToolResultsReady(listOf(final("c1"))),
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(false, d.actions.any { it is Action.SendToolResponse })
    }

    @Test fun onlyTheSentResultsOfAPartlyStaleBatchCount() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"), fc("c2"))),
                Event.ToolCancelled(listOf("c1")), // the server cancels c1, which finishes anyway, marked
                Event.ToolResultsReady(listOf(final("c1"), plain("c2"))),
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(listOf("c2"), d.actions.filterIsInstance<Action.SendToolResponse>().single().results.map { it.id })
    }

    @Test fun resultsSentAfterTurnCompleteWaitForTheSignOff() {
        val toolTurn = listOf(
            Event.Ready,
            Event.ToolCallReceived(listOf(fc("c1"))),
            Event.TurnComplete,
            Event.ToolResultsReady(listOf(final("c1"))),
        )
        val waiting = reduceAll(toolTurn)
        assertEquals(Phase.SPEAKING, waiting.state.phase) // the model still owes its sign-off
        assertEquals(Action.ArmStallTimer, waiting.actions.last()) // ...or the stall ends it

        val signedOff = reduceAll(
            toolTurn + listOf(Event.ModelActivity, Event.PlaybackBusy, Event.TurnComplete, Event.PlaybackIdle),
        )
        assertEquals(Phase.ENDED, signedOff.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.End), signedOff.actions.takeLast(2))

        val silent = reduceAll(toolTurn + Event.StallTimeout)
        assertEquals(Phase.ENDED, silent.state.phase)
    }

    @Test fun theToolCallTurnsOwnTurnCompleteArrivingAfterTheResultsDoesNotEndTheFinalTurn() {
        // Gemini 3.8 completes the tool-call turn before it hears our response, and a fast tool's result is sent
        // before that turnComplete lands; the sign-off then comes as its own turn.
        val toolTurn = listOf(
            Event.Ready,
            Event.ToolCallReceived(listOf(fc("c1"))),
            Event.ToolResultsReady(listOf(final("c1"))),
            Event.TurnComplete,
        )
        val waiting = reduceAll(toolTurn)
        assertEquals(Phase.SPEAKING, waiting.state.phase) // the model still owes its sign-off
        assertEquals(false, Action.CancelStallTimer in waiting.actions) // ...or the stall ends it

        val signedOff = reduceAll(
            toolTurn + listOf(Event.ModelActivity, Event.PlaybackBusy, Event.TurnComplete, Event.PlaybackIdle),
        )
        assertEquals(Phase.ENDED, signedOff.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.End), signedOff.actions.takeLast(2))

        val silent = reduceAll(toolTurn + Event.StallTimeout)
        assertEquals(Phase.ENDED, silent.state.phase)
    }

    @Test fun aLateTranscriptOfTheToolCallTurnDoesNotEndTheFinalTurnEarly() {
        // An output transcript (or a text-only generating frame) of words spoken before the tool call can land
        // after our results, ahead of that turn's own turnComplete: only the sign-off's audio begins the reply.
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(final("c1"))),
                Event.ModelActivity,
                Event.TurnComplete,
            ),
        )
        assertEquals(Phase.SPEAKING, d.state.phase)
    }
}
