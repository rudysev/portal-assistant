package com.portal.assistant.conversation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive tests of the pure conversation reducer — especially the event orderings that are miserable
 * to reproduce on device: turnComplete-vs-drain races, the no-audio turn, mid-turn underrun, interrupt.
 */
class ConversationStateTest {

    private fun fc(id: String) = FunctionCall(id, "portal.test", JSONObject())
    private fun tr(id: String) = ToolResult(id, "portal.test", JSONObject())

    private fun reduceAll(events: List<Event>, multiTurn: Boolean = false): Decision {
        var state = ConversationState()
        val actions = mutableListOf<Action>()
        for (e in events) {
            val d = reduce(state, e, multiTurn)
            state = d.state
            actions += d.actions
        }
        return Decision(state, actions)
    }

    // ---- CONNECTING --------------------------------------------------------------------------------

    @Test fun readyEntersListeningAndStartsForwarding() {
        val d = reduce(ConversationState(), Event.Ready, multiTurn = false)
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(listOf(Action.StartForwarding, Action.ArmNoSpeechTimer), d.actions)
    }

    @Test fun connectFailureEnds() {
        val d = reduce(ConversationState(), Event.Disconnected, multiTurn = false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    @Test fun strayEventsWhileConnectingAreIgnored() {
        for (e in listOf(Event.PlaybackIdle, Event.TurnComplete, Event.UserSpeaking, Event.StallTimeout)) {
            val d = reduce(ConversationState(), e, multiTurn = false)
            assertEquals(Phase.CONNECTING, d.state.phase)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }

    // ---- LISTENING ---------------------------------------------------------------------------------

    @Test fun userSpeakingReArmsNoSpeechTimer() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.UserSpeaking, multiTurn = false)
        assertEquals(Phase.LISTENING, d.state.phase) // no transition
        assertEquals(listOf(Action.ArmNoSpeechTimer), d.actions)
    }

    @Test fun modelStartedMutesMicAndArmsStall() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.ModelStarted, multiTurn = false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(
            listOf(Action.StopForwarding, Action.CancelNoSpeechTimer, Action.ArmStallTimer),
            d.actions,
        )
    }

    @Test fun noSpeechTimeoutEndsConversation() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.NoSpeechTimeout, multiTurn = false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    @Test fun disconnectWhileListeningEnds() {
        val d = reduce(ConversationState(phase = Phase.LISTENING), Event.Disconnected, false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    // ---- SPEAKING turn-end races -------------------------------------------------------------------

    @Test fun audioThenTurnCompleteThenDrainEndsTurn_singleTurn() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.UserSpeaking,
                Event.ModelStarted,
                Event.PlaybackBusy, // audio playing
                Event.TurnComplete, // server done, but audio still playing → no end yet
                Event.PlaybackIdle, // now drained → end
            ),
        )
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.End), d.actions.takeLast(3))
    }

    @Test fun drainBeforeTurnCompleteEndsOnTurnComplete() {
        // The other ordering: audio finishes playing, THEN the server marks the turn complete.
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy, false).state
        s = reduce(s, Event.PlaybackIdle, false).state // drained, but turnComplete not yet seen
        assertEquals(Phase.SPEAKING, s.phase) // must NOT end early
        val d = reduce(s, Event.TurnComplete, false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.End), d.actions)
    }

    @Test fun noAudioTurnEndsImmediatelyOnTurnComplete() {
        // Model takes the turn but never emits audio (e.g. an empty/aborted turn): playbackIdle stays
        // true, so turnComplete alone ends it.
        var s = ConversationState(phase = Phase.SPEAKING) // playbackIdle defaults true
        val d = reduce(s, Event.TurnComplete, false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.End), d.actions)
    }

    @Test fun midTurnUnderrunDoesNotEndEarly() {
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy, false).state // chunk 1
        s = reduce(s, Event.PlaybackIdle, false).state // queue emptied between bursts
        assertEquals(Phase.SPEAKING, s.phase)
        s = reduce(s, Event.PlaybackBusy, false).state // chunk 2 arrives
        assertEquals(false, s.playbackIdle)
        s = reduce(s, Event.TurnComplete, false).state // server done; chunk 2 still playing
        assertEquals(Phase.SPEAKING, s.phase)
        val d = reduce(s, Event.PlaybackIdle, false) // chunk 2 drained → end
        assertEquals(Phase.ENDED, d.state.phase)
    }

    @Test fun audioChunksCancelStallTimer() {
        // Audio flowing → cancel the dead-air stall (it's only armed when nothing is playing).
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.PlaybackBusy, false)
        assertEquals(listOf(Action.CancelStallTimer), d.actions)
        assertEquals(false, d.state.playbackIdle)
    }

    @Test fun drainWithoutTurnCompleteArmsStall() {
        // Playback drained but the server hasn't said turnComplete → arm the dead-air clock, stay SPEAKING.
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy, false).state // audio played
        val d = reduce(s, Event.PlaybackIdle, false) // queue drained, turn not complete
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(true, d.state.playbackIdle)
        assertEquals(listOf(Action.ArmStallTimer), d.actions)
    }

    @Test fun fullTurnArmsStallOnDrainThenEndsOnTurnComplete() {
        // End-to-end stall policy: audio plays, drains (stall armed), then the server completes → end.
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ModelStarted, // ArmStallTimer (pre-audio gap)
                Event.PlaybackBusy, // CancelStallTimer (audio flowing)
                Event.PlaybackIdle, // drained, not complete → ArmStallTimer (dead-air clock)
                Event.TurnComplete, // both conditions met → end
            ),
        )
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(
            listOf(
                Action.StartForwarding, Action.ArmNoSpeechTimer, // Ready
                Action.StopForwarding, Action.CancelNoSpeechTimer, Action.ArmStallTimer, // ModelStarted
                Action.CancelStallTimer, // PlaybackBusy
                Action.ArmStallTimer, // PlaybackIdle (drained, not complete)
                Action.FireAfterSpeech, Action.CancelStallTimer, Action.End, // TurnComplete → end
            ),
            d.actions,
        )
    }

    @Test fun drainThenStallTimeoutReListensMultiTurn() {
        // Server truncates: audio drains with no turnComplete (stall armed), the stall fires → re-listen.
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ModelStarted,
                Event.PlaybackBusy,
                Event.PlaybackIdle, // drained, not complete → ArmStallTimer
                Event.StallTimeout, // nothing more came → re-listen
            ),
            multiTurn = true,
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(
            listOf(
                Action.FireAfterSpeech,
                Action.FlushPlayback,
                Action.CancelStallTimer,
                Action.StartForwarding,
                Action.ArmNoSpeechTimer,
            ),
            d.actions.takeLast(5),
        )
    }

    @Test fun stallTimeoutEndsConversationSingleTurn() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.StallTimeout, multiTurn = false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.End), d.actions)
    }

    @Test fun stallTimeoutReListensMultiTurn() {
        // A truncated/wedged turn flushes the partial audio and hands the turn back, not hang up.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, playbackIdle = false),
            Event.StallTimeout,
            multiTurn = true,
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(
            listOf(
                Action.FireAfterSpeech,
                Action.FlushPlayback,
                Action.CancelStallTimer,
                Action.StartForwarding,
                Action.ArmNoSpeechTimer,
            ),
            d.actions,
        )
    }

    @Test fun interruptedFlushesAndStaysSpeaking() {
        // A turn that was about to end gets interrupted/revised: reset and keep waiting; the
        // flush-induced drain must NOT end the turn.
        var s = ConversationState(phase = Phase.SPEAKING, turnComplete = true, playbackIdle = false)
        val d = reduce(s, Event.Interrupted, false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(false, d.state.turnComplete)
        assertEquals(true, d.state.playbackIdle)
        assertEquals(listOf(Action.FlushPlayback, Action.ArmStallTimer), d.actions)
        // The flush triggers a PlaybackIdle; with turnComplete reset, it must not end the turn.
        val after = reduce(d.state, Event.PlaybackIdle, false)
        assertEquals(Phase.SPEAKING, after.state.phase)
    }

    @Test fun disconnectWhileSpeakingEnds() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.Disconnected, false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    @Test fun strayEventsWhileSpeakingIgnored() {
        // UserSpeaking/Ready/ModelStarted shouldn't drive anything mid-speech.
        for (e in listOf(Event.UserSpeaking, Event.Ready, Event.ModelStarted, Event.NoSpeechTimeout)) {
            val d = reduce(ConversationState(phase = Phase.SPEAKING), e, false)
            assertEquals(Phase.SPEAKING, d.state.phase)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }

    // ---- MULTI-TURN (Phase 2.b) --------------------------------------------------------------------

    @Test fun multiTurnReListensInsteadOfEnding() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ModelStarted,
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle, // turn ends → should re-listen, not end
            ),
            multiTurn = true,
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(
            listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.StartForwarding, Action.ArmNoSpeechTimer),
            d.actions.takeLast(4),
        )
    }

    @Test fun multiTurnEndsOnFollowUpSilence() {
        var s = ConversationState(phase = Phase.LISTENING) // after a re-listen
        val d = reduce(s, Event.NoSpeechTimeout, multiTurn = true)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    // ---- Tool calls (Phase 2) ----------------------------------------------------------------------

    @Test fun toolCallFromListeningEntersSpeakingAndExecutes() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.ToolCallReceived(listOf(fc("c1"))), false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(setOf("c1"), d.state.toolsInFlight)
        assertTrue(d.actions.contains(Action.StopForwarding))
        assertTrue(d.actions.contains(Action.CancelNoSpeechTimer))
        assertTrue(d.actions.contains(Action.ArmStallTimer))
        val exec = d.actions.filterIsInstance<Action.ExecuteTools>().first()
        assertEquals(listOf("c1"), exec.calls.map { it.id })
    }

    @Test fun staleToolResultsIgnoredAfterInterrupt() {
        // After Interrupted clears toolsInFlight, a late ToolResultsReady for the cancelled ID
        // must be silently dropped — not sent to the model and must not reset the stall timer.
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = emptySet())
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))), false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        assertTrue(d.actions.none { it is Action.SendToolResponse })
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun toolCallFromSpeakingCancelsStallAndExecutes() {
        val speaking = ConversationState(phase = Phase.SPEAKING)
        val d = reduce(speaking, Event.ToolCallReceived(listOf(fc("c1"))), false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(setOf("c1"), d.state.toolsInFlight)
        assertTrue(d.actions.contains(Action.CancelStallTimer))
        val exec = d.actions.filterIsInstance<Action.ExecuteTools>().first()
        assertEquals(listOf("c1"), exec.calls.map { it.id })
    }

    @Test fun toolResultsReadySendsAndArmsStallWhenAllDone() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))), false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        val send = d.actions.filterIsInstance<Action.SendToolResponse>().first()
        assertEquals(listOf("c1"), send.results.map { it.id })
        assertTrue(d.actions.contains(Action.ArmStallTimer))
    }

    @Test fun toolResultsReadyNoStallWhenMoreInFlight() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1", "c2"))
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))), false)
        assertEquals(setOf("c2"), d.state.toolsInFlight)
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun turnCompleteWhileToolsInFlightDoesNotEnd() {
        val speaking = ConversationState(phase = Phase.SPEAKING, playbackIdle = true, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.TurnComplete, false)
        assertEquals(Phase.SPEAKING, d.state.phase) // must NOT end
        assertEquals(emptyList<Action>(), d.actions)
    }

    @Test fun stallTimeoutIgnoredWhileToolsInFlight() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.StallTimeout, false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptyList<Action>(), d.actions)
    }

    @Test fun toolCancelledArmsStallWhenSetEmpties() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.ToolCancelled(listOf("c1")), false)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        assertTrue(d.actions.contains(Action.ArmStallTimer))
    }

    @Test fun toolCancelledPartialLeavesRemainingInFlight() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1", "c2"))
        val d = reduce(speaking, Event.ToolCancelled(listOf("c1")), false)
        assertEquals(setOf("c2"), d.state.toolsInFlight)
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun interruptedResetsClearsTools() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.Interrupted, false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        assertTrue(d.actions.contains(Action.FlushPlayback))
        assertTrue(d.actions.contains(Action.ArmStallTimer))
    }

    @Test fun chainedToolRoundsReListen() {
        // ToolCall → Results → ToolCall → Results → TurnComplete → PlaybackIdle → LISTENING
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(tr("c1"))),
                Event.ToolCallReceived(listOf(fc("c2"))),
                Event.ToolResultsReady(listOf(tr("c2"))),
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
            multiTurn = true,
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
    }

    @Test fun turnCompleteAfterToolsAndAudioEndsCorrectly() {
        // Turn: ToolCall received while listening, results sent, audio plays, then turnComplete + drain.
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(tr("c1"))),
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
            multiTurn = false,
        )
        assertEquals(Phase.ENDED, d.state.phase)
    }

    // ---- AfterSpeech (deferred side effects fire on real turn-end) ---------------------------------

    @Test fun fireAfterSpeechEmittedOnTurnEndSingleTurn() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING, playbackIdle = true), Event.TurnComplete, false)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(Action.FireAfterSpeech, d.actions.first()) // before End, so it applies pre-teardown
    }

    @Test fun fireAfterSpeechEmittedOnTurnEndMultiTurn() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING, playbackIdle = true), Event.TurnComplete, true)
        assertEquals(Phase.LISTENING, d.state.phase)
        assertTrue(d.actions.contains(Action.FireAfterSpeech))
    }

    @Test fun fireAfterSpeechEmittedOnStalledTurnEnd() {
        // A wedged turn still applies the deferred effect (the confirmation already drained).
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.StallTimeout, true)
        assertTrue(d.actions.contains(Action.FireAfterSpeech))
    }

    @Test fun fireAfterSpeechNotEmittedMidAnswerDrain() {
        // Drain before turnComplete keeps SPEAKING → must NOT apply the effect yet (Pin #1).
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy, false).state
        val d = reduce(s, Event.PlaybackIdle, false)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertTrue(d.actions.none { it == Action.FireAfterSpeech })
    }

    @Test fun fireAfterSpeechNotEmittedOnInterrupt() {
        // A barge-in defers the effect to the post-interrupt turn-end, not now.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, turnComplete = true, playbackIdle = false),
            Event.Interrupted,
            false,
        )
        assertTrue(d.actions.none { it == Action.FireAfterSpeech })
    }

    @Test fun fireAfterSpeechNotEmittedWhileToolsInFlight() {
        // turnComplete arrives but a tool is still running → no turn-end, no effect.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, playbackIdle = true, toolsInFlight = setOf("c1")),
            Event.TurnComplete,
            false,
        )
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertTrue(d.actions.none { it == Action.FireAfterSpeech })
    }

    // ---- ENDED terminal ----------------------------------------------------------------------------

    @Test fun endedSwallowsEverything() {
        val ended = ConversationState(phase = Phase.ENDED)
        val allEvents = listOf(
            Event.Ready, Event.UserSpeaking, Event.ModelStarted, Event.PlaybackBusy, Event.PlaybackIdle,
            Event.TurnComplete, Event.Interrupted, Event.NoSpeechTimeout, Event.StallTimeout, Event.Disconnected,
            Event.ToolCallReceived(emptyList()), Event.ToolResultsReady(emptyList()), Event.ToolCancelled(emptyList()),
        )
        for (e in allEvents) {
            val d = reduce(ended, e, false)
            assertEquals(Phase.ENDED, d.state.phase)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }
}
