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
}
