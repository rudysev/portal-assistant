package com.portal.assistant.conversation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive tests of the pure conversation reducer — especially the event orderings that are miserable
 * to reproduce on device: turnComplete-vs-drain races, the no-audio turn, mid-turn underrun, interrupt, and the
 * final turn. The stall's Arm/Cancel table is [StallDecisionTest]'s.
 */
class ConversationStateTest {

    private fun fc(id: String) = FunctionCall(id, "portal.test", JSONObject())
    private fun tr(id: String) = ToolResult(id, "portal.test", JSONObject())
    private fun final(id: String) = ToolResult(id, "portal.test", JSONObject(), finalTurn = true)

    // A turn end hands the mic back for a follow-up; only a final turn ends the conversation there.
    private val reListen =
        listOf(Action.FireAfterSpeech, Action.StartForwarding, Action.ArmNoSpeechTimer, Action.CancelStallTimer)

    private fun reduceAll(events: List<Event>): Decision {
        var state = ConversationState()
        val actions = mutableListOf<Action>()
        for (e in events) {
            val d = reduce(state, e)
            state = d.state
            actions += d.actions
        }
        return Decision(state, actions)
    }

    // ---- CONNECTING --------------------------------------------------------------------------------

    @Test fun readyEntersListeningAndStartsForwarding() {
        val d = reduce(ConversationState(), Event.Ready)
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(listOf(Action.StartForwarding, Action.ArmNoSpeechTimer), d.actions)
    }

    @Test fun connectFailureEnds() {
        val d = reduce(ConversationState(), Event.Disconnected)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    @Test fun strayEventsWhileConnectingAreIgnored() {
        for (e in listOf(Event.PlaybackIdle, Event.TurnComplete, Event.UserSpeaking, Event.StallTimeout)) {
            val d = reduce(ConversationState(), e)
            assertEquals(Phase.CONNECTING, d.state.phase)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }

    // ---- LISTENING ---------------------------------------------------------------------------------

    @Test fun userSpeakingReArmsNoSpeechTimer() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.UserSpeaking)
        assertEquals(Phase.LISTENING, d.state.phase) // no transition
        assertEquals(listOf(Action.ArmNoSpeechTimer), d.actions)
    }

    @Test fun modelActivityMutesMicAndArmsStall() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.ModelActivity)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(
            listOf(Action.StopForwarding, Action.CancelNoSpeechTimer, Action.ArmStallTimer),
            d.actions,
        )
    }

    @Test fun noSpeechTimeoutEndsConversation() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.NoSpeechTimeout)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    @Test fun disconnectWhileListeningEnds() {
        val d = reduce(ConversationState(phase = Phase.LISTENING), Event.Disconnected)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    // ---- SPEAKING turn-end races -------------------------------------------------------------------

    @Test fun audioThenTurnCompleteThenDrainReListens() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.UserSpeaking,
                Event.ModelActivity,
                Event.PlaybackBusy, // audio playing
                Event.TurnComplete, // server done, but audio still playing → no turn end yet
                Event.PlaybackIdle, // now drained → turn end
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(reListen, d.actions.takeLast(4))
    }

    @Test fun drainBeforeTurnCompleteEndsTheTurnOnTurnComplete() {
        // The other ordering: audio finishes playing, THEN the server marks the turn complete.
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy).state
        s = reduce(s, Event.PlaybackIdle).state // drained, but turnComplete not yet seen
        assertEquals(Phase.SPEAKING, s.phase) // must NOT end early
        val d = reduce(s, Event.TurnComplete)
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(reListen, d.actions)
    }

    @Test fun noAudioTurnEndsImmediatelyOnTurnComplete() {
        // Model takes the turn but never emits audio (e.g. an empty/aborted turn): playbackIdle stays
        // true, so turnComplete alone ends it.
        val s = ConversationState(phase = Phase.SPEAKING) // playbackIdle defaults true
        val d = reduce(s, Event.TurnComplete)
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(reListen, d.actions)
    }

    @Test fun midTurnUnderrunDoesNotEndEarly() {
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy).state // chunk 1
        s = reduce(s, Event.PlaybackIdle).state // queue emptied between bursts
        assertEquals(Phase.SPEAKING, s.phase)
        s = reduce(s, Event.PlaybackBusy).state // chunk 2 arrives
        assertEquals(false, s.playbackIdle)
        s = reduce(s, Event.TurnComplete).state // server done; chunk 2 still playing
        assertEquals(Phase.SPEAKING, s.phase)
        val d = reduce(s, Event.PlaybackIdle) // chunk 2 drained → turn end
        assertEquals(Phase.LISTENING, d.state.phase)
    }

    @Test fun audioChunksCancelStallTimer() {
        // Audio flowing → cancel the dead-air stall (it's only armed when nothing is playing).
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.PlaybackBusy)
        assertEquals(listOf(Action.CancelStallTimer), d.actions)
        assertEquals(false, d.state.playbackIdle)
    }

    @Test fun drainWithoutTurnCompleteArmsStall() {
        // Playback drained but the server hasn't said turnComplete → arm the dead-air clock, stay SPEAKING.
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy).state // audio played
        val d = reduce(s, Event.PlaybackIdle) // queue drained, turn not complete
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(true, d.state.playbackIdle)
        assertEquals(listOf(Action.ArmStallTimer), d.actions)
    }

    @Test fun fullTurnArmsStallOnDrainThenReListensOnTurnComplete() {
        // End-to-end stall policy: audio plays, drains (stall armed), then the server completes → turn end.
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ModelActivity, // ArmStallTimer (pre-audio gap)
                Event.PlaybackBusy, // CancelStallTimer (audio flowing)
                Event.PlaybackIdle, // drained, not complete → ArmStallTimer (dead-air clock)
                Event.TurnComplete, // both conditions met → turn end
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        val beforeTurnEnd = listOf(
            Action.StartForwarding,
            Action.ArmNoSpeechTimer, // Ready
            Action.StopForwarding,
            Action.CancelNoSpeechTimer,
            Action.ArmStallTimer, // ModelActivity
            Action.CancelStallTimer, // PlaybackBusy
            Action.ArmStallTimer, // PlaybackIdle (drained, not complete)
        )
        assertEquals(beforeTurnEnd + reListen, d.actions) // TurnComplete → re-listen
    }

    @Test fun drainThenStallTimeoutReListens() {
        // Server truncates: audio drains with no turnComplete (stall armed), the stall fires → re-listen.
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ModelActivity,
                Event.PlaybackBusy,
                Event.PlaybackIdle, // drained, not complete → ArmStallTimer
                Event.StallTimeout, // nothing more came → re-listen
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(
            listOf(
                Action.FireAfterSpeech,
                Action.FlushPlayback,
                Action.StartForwarding,
                Action.ArmNoSpeechTimer,
                Action.CancelStallTimer, // appended by stallDecision
            ),
            d.actions.takeLast(5),
        )
    }

    @Test fun stallTimeoutReListens() {
        // A truncated/wedged turn flushes the partial audio and hands the turn back, not hang up.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, playbackIdle = false),
            Event.StallTimeout,
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(
            listOf(
                Action.FireAfterSpeech,
                Action.FlushPlayback,
                Action.StartForwarding,
                Action.ArmNoSpeechTimer,
                Action.CancelStallTimer,
            ),
            d.actions,
        )
    }

    @Test fun interruptedFlushesAndStaysSpeaking() {
        // A turn that was about to end gets interrupted/revised: reset and keep waiting; the
        // flush-induced drain must NOT end the turn.
        val s = ConversationState(phase = Phase.SPEAKING, turnComplete = true, playbackIdle = false)
        val d = reduce(s, Event.Interrupted)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(false, d.state.turnComplete)
        assertEquals(true, d.state.playbackIdle)
        assertEquals(listOf(Action.FlushPlayback, Action.ArmStallTimer), d.actions)
        // The flush triggers a PlaybackIdle; with turnComplete reset, it must not end the turn.
        val after = reduce(d.state, Event.PlaybackIdle)
        assertEquals(Phase.SPEAKING, after.state.phase)
    }

    @Test fun disconnectWhileSpeakingEnds() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.Disconnected)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(listOf(Action.End), d.actions)
    }

    @Test fun strayEventsWhileSpeakingIgnored() {
        // UserSpeaking/Ready/NoSpeechTimeout shouldn't drive anything mid-speech.
        for (e in listOf(Event.UserSpeaking, Event.Ready, Event.NoSpeechTimeout)) {
            val d = reduce(ConversationState(phase = Phase.SPEAKING), e)
            assertEquals(Phase.SPEAKING, d.state.phase)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }

    @Test fun modelActivityWhileSpeakingRearmsStallWhenIdle() {
        // Dead-air gap (pre-audio / post-drain): generating / transcript refresh the clock.
        val d = reduce(ConversationState(phase = Phase.SPEAKING, playbackIdle = true), Event.ModelActivity)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(listOf(Action.ArmStallTimer), d.actions)
    }

    @Test fun modelActivityWhilePlayingDoesNotRearmStall() {
        // Audio is queued/playing — re-arming would cut a long answer mid-sentence once the server
        // stops streaming (Gemini often sends OutputTranscript after Audio in the same message).
        val d = reduce(ConversationState(phase = Phase.SPEAKING, playbackIdle = false), Event.ModelActivity)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptyList<Action>(), d.actions)
    }

    @Test fun modelActivityWhileToolsInFlightDoesNotRearmStall() {
        // Tools own the turn; StallTimeout is ignored until they drain — don't fight CancelStallTimer.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, playbackIdle = true, toolsInFlight = setOf("c1")),
            Event.ModelActivity,
        )
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptyList<Action>(), d.actions)
    }

    // ---- Tool calls (Phase 2) ----------------------------------------------------------------------

    @Test fun toolCallFromListeningEntersSpeakingAndExecutes() {
        val listening = ConversationState(phase = Phase.LISTENING)
        val d = reduce(listening, Event.ToolCallReceived(listOf(fc("c1"))))
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
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))))
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        assertTrue(d.actions.none { it is Action.SendToolResponse })
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun toolCallFromSpeakingCancelsStallAndExecutes() {
        val speaking = ConversationState(phase = Phase.SPEAKING)
        val d = reduce(speaking, Event.ToolCallReceived(listOf(fc("c1"))))
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(setOf("c1"), d.state.toolsInFlight)
        assertTrue(d.actions.contains(Action.CancelStallTimer))
        val exec = d.actions.filterIsInstance<Action.ExecuteTools>().first()
        assertEquals(listOf("c1"), exec.calls.map { it.id })
    }

    @Test fun toolResultsReadySendsAndArmsStallWhenAllDone() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))))
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        val send = d.actions.filterIsInstance<Action.SendToolResponse>().first()
        assertEquals(listOf("c1"), send.results.map { it.id })
        assertTrue(d.actions.contains(Action.ArmStallTimer))
    }

    @Test fun toolResultsReadyNoStallWhilePlaying() {
        // Tools drained but audio is still queued — arming would let StallTimeout cut mid-sentence.
        val speaking = ConversationState(phase = Phase.SPEAKING, playbackIdle = false, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))))
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        assertTrue(d.actions.filterIsInstance<Action.SendToolResponse>().isNotEmpty())
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun toolResultsReadyNoStallWhenMoreInFlight() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1", "c2"))
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))))
        assertEquals(setOf("c2"), d.state.toolsInFlight)
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun turnCompleteWhileToolsInFlightDoesNotEnd() {
        val speaking = ConversationState(phase = Phase.SPEAKING, playbackIdle = true, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.TurnComplete)
        assertEquals(Phase.SPEAKING, d.state.phase) // must NOT end
        assertEquals(emptyList<Action>(), d.actions)
    }

    @Test fun toolResultsReadyAfterTurnCompleteAwaitsTheAnswer() {
        // That turnComplete was the tool-call turn's own: the sent results owe an answer, so the turn stays open
        // (stall armed) and the turn ends on the answer's turnComplete.
        val speaking = ConversationState(
            phase = Phase.SPEAKING,
            turnComplete = true,
            playbackIdle = true,
            toolsInFlight = setOf("c1"),
        )
        val d = reduce(speaking, Event.ToolResultsReady(listOf(tr("c1"))))
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(false, d.state.turnComplete)
        assertTrue(d.actions.filterIsInstance<Action.SendToolResponse>().isNotEmpty())
        assertTrue(d.actions.contains(Action.ArmStallTimer))

        var state = d.state
        for (e in listOf(Event.PlaybackBusy, Event.TurnComplete, Event.PlaybackIdle)) {
            state = reduce(state, e).state
        }
        assertEquals(Phase.LISTENING, state.phase)
    }

    @Test fun toolCancelledEndsTheTurnWhenAlreadyComplete() {
        val speaking = ConversationState(
            phase = Phase.SPEAKING,
            turnComplete = true,
            playbackIdle = true,
            toolsInFlight = setOf("c1"),
        )
        val d = reduce(speaking, Event.ToolCancelled(listOf("c1")))
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(reListen, d.actions)
    }

    @Test fun stallTimeoutIgnoredWhileToolsInFlight() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.StallTimeout)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertEquals(emptyList<Action>(), d.actions)
    }

    @Test fun toolCancelledArmsStallWhenSetEmpties() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.ToolCancelled(listOf("c1")))
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
        assertTrue(d.actions.contains(Action.ArmStallTimer))
    }

    @Test fun toolCancelledPartialLeavesRemainingInFlight() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1", "c2"))
        val d = reduce(speaking, Event.ToolCancelled(listOf("c1")))
        assertEquals(setOf("c2"), d.state.toolsInFlight)
        assertTrue(d.actions.none { it is Action.ArmStallTimer })
    }

    @Test fun interruptedResetsClearsTools() {
        val speaking = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        val d = reduce(speaking, Event.Interrupted)
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
                Event.PlaybackBusy,
                Event.TurnComplete,
                Event.PlaybackIdle,
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(emptySet<String>(), d.state.toolsInFlight)
    }

    // Gemini 3.8 completes the tool-call turn before it hears our response, then answers in a turn of its own.
    private fun answerAfter(toolTurn: List<Event>): List<Event> =
        listOf(Event.Ready, Event.ToolCallReceived(listOf(fc("c1")))) + toolTurn +
            listOf(Event.ModelActivity, Event.PlaybackBusy, Event.TurnComplete, Event.PlaybackIdle)

    @Test fun theToolCallTurnsOwnTurnCompleteAfterTheResultsDoesNotReopenTheMic() {
        // A fast tool: our results are sent before that turn's turnComplete lands.
        val toolTurn = listOf(Event.ToolResultsReady(listOf(tr("c1"))), Event.TurnComplete)
        val waiting = reduceAll(listOf(Event.Ready, Event.ToolCallReceived(listOf(fc("c1")))) + toolTurn)
        assertEquals(Phase.SPEAKING, waiting.state.phase)
        assertEquals(1, waiting.actions.count { it == Action.StartForwarding }) // only the opening's
        assertEquals(1, waiting.actions.count { it == Action.ArmNoSpeechTimer }) // no walk-away clock on the answer
        assertEquals(false, Action.CancelStallTimer in waiting.actions) // the stall covers an answer that never comes

        val answered = reduceAll(answerAfter(toolTurn))
        assertEquals(Phase.LISTENING, answered.state.phase)
        assertEquals(2, answered.actions.count { it == Action.StartForwarding }) // reopened after the answer only
    }

    @Test fun theToolCallTurnsOwnTurnCompleteBeforeTheResultsDoesNotReopenTheMic() {
        // A slow tool: that turn's turnComplete lands while the tool runs.
        val toolTurn = listOf(Event.TurnComplete, Event.ToolResultsReady(listOf(tr("c1"))))
        val waiting = reduceAll(listOf(Event.Ready, Event.ToolCallReceived(listOf(fc("c1")))) + toolTurn)
        assertEquals(Phase.SPEAKING, waiting.state.phase)
        assertEquals(1, waiting.actions.count { it == Action.StartForwarding })
        assertEquals(1, waiting.actions.count { it == Action.ArmNoSpeechTimer })
        assertTrue(Action.ArmStallTimer in waiting.actions)

        val answered = reduceAll(answerAfter(toolTurn))
        assertEquals(Phase.LISTENING, answered.state.phase)
        assertEquals(2, answered.actions.count { it == Action.StartForwarding })
    }

    @Test fun aToolAnswerThatNeverComesReListensOnTheStall() {
        val d = reduceAll(
            listOf(
                Event.Ready,
                Event.ToolCallReceived(listOf(fc("c1"))),
                Event.ToolResultsReady(listOf(tr("c1"))),
                Event.TurnComplete,
                Event.StallTimeout,
            ),
        )
        assertEquals(Phase.LISTENING, d.state.phase)
        assertEquals(listOf(Action.StartForwarding, Action.ArmNoSpeechTimer), d.actions.takeLast(3).take(2))
    }

    // ---- AfterSpeech (deferred side effects fire on real turn-end) ---------------------------------

    @Test fun fireAfterSpeechPrecedesEndOnAFinalTurn() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING, finalTurn = true), Event.TurnComplete)
        assertEquals(Phase.ENDED, d.state.phase)
        assertEquals(Action.FireAfterSpeech, d.actions.first()) // before End, so it applies pre-teardown
    }

    @Test fun fireAfterSpeechEmittedOnTurnEnd() {
        val d = reduce(ConversationState(phase = Phase.SPEAKING, playbackIdle = true), Event.TurnComplete)
        assertEquals(Phase.LISTENING, d.state.phase)
        assertTrue(d.actions.contains(Action.FireAfterSpeech))
    }

    @Test fun fireAfterSpeechEmittedOnStalledTurnEnd() {
        // A wedged turn still applies the deferred effect (the confirmation already drained).
        val d = reduce(ConversationState(phase = Phase.SPEAKING), Event.StallTimeout)
        assertTrue(d.actions.contains(Action.FireAfterSpeech))
    }

    @Test fun fireAfterSpeechNotEmittedMidAnswerDrain() {
        // Drain before turnComplete keeps SPEAKING → must NOT apply the effect yet (Pin #1).
        var s = ConversationState(phase = Phase.SPEAKING)
        s = reduce(s, Event.PlaybackBusy).state
        val d = reduce(s, Event.PlaybackIdle)
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertTrue(d.actions.none { it == Action.FireAfterSpeech })
    }

    @Test fun fireAfterSpeechNotEmittedOnInterrupt() {
        // A barge-in defers the effect to the post-interrupt turn-end, not now.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, turnComplete = true, playbackIdle = false),
            Event.Interrupted,
        )
        assertTrue(d.actions.none { it == Action.FireAfterSpeech })
    }

    @Test fun fireAfterSpeechNotEmittedWhileToolsInFlight() {
        // turnComplete arrives but a tool is still running → no turn-end, no effect.
        val d = reduce(
            ConversationState(phase = Phase.SPEAKING, playbackIdle = true, toolsInFlight = setOf("c1")),
            Event.TurnComplete,
        )
        assertEquals(Phase.SPEAKING, d.state.phase)
        assertTrue(d.actions.none { it == Action.FireAfterSpeech })
    }

    // ---- Final turn ----------------------------------------------------------------------------------
    // Once a result marked [ToolResult.finalTurn] is sent to the model, the next turn end — normal or stalled — ends
    // the conversation instead of re-listening, even across an interrupt. A marked result that isn't sent ends nothing.

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
                Event.ToolResultsReady(listOf(final("c1"), tr("c2"))),
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

    // ---- ENDED terminal ----------------------------------------------------------------------------

    @Test fun endedSwallowsEverything() {
        val ended = ConversationState(phase = Phase.ENDED)
        val allEvents = listOf(
            Event.Ready, Event.UserSpeaking, Event.ModelActivity, Event.PlaybackBusy, Event.PlaybackIdle,
            Event.TurnComplete, Event.Interrupted, Event.NoSpeechTimeout, Event.StallTimeout, Event.Disconnected,
            Event.ToolCallReceived(emptyList()), Event.ToolResultsReady(emptyList()), Event.ToolCancelled(emptyList()),
        )
        for (e in allEvents) {
            val d = reduce(ended, e)
            assertEquals(Phase.ENDED, d.state.phase)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }
}
