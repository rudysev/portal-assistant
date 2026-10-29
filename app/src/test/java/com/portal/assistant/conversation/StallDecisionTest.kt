package com.portal.assistant.conversation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The [stallDecision] table: the single source of truth for the stall's Arm / Cancel / None. */
class StallDecisionTest {

    private fun fc(id: String) = FunctionCall(id, "portal.test", JSONObject())
    private fun tr(id: String) = ToolResult(id, "portal.test", JSONObject())

    private fun assertStall(state: ConversationState, event: Event, expected: StallDecision) {
        assertEquals("$event @ ${state.phase}", expected, stallDecision(state, event))
    }

    @Test fun stallDecision_connectingAndEndedAreNone() {
        for (e in listOf(
            Event.Ready,
            Event.ModelActivity,
            Event.PlaybackBusy,
            Event.StallTimeout,
            Event.ToolCallReceived(listOf(fc("c1"))),
        )) {
            assertStall(ConversationState(phase = Phase.CONNECTING), e, StallDecision.None)
            assertStall(ConversationState(phase = Phase.ENDED), e, StallDecision.None)
        }
    }

    @Test fun stallDecision_listeningEntryArms() {
        val listening = ConversationState(phase = Phase.LISTENING)
        assertStall(listening, Event.ModelActivity, StallDecision.Arm)
        assertStall(listening, Event.ToolCallReceived(listOf(fc("c1"))), StallDecision.Arm)
        assertStall(listening, Event.UserSpeaking, StallDecision.None)
        assertStall(listening, Event.NoSpeechTimeout, StallDecision.None)
        assertStall(listening, Event.PlaybackBusy, StallDecision.None)
    }

    @Test fun stallDecision_speakingPlaybackBusyCancels() {
        assertStall(ConversationState(phase = Phase.SPEAKING), Event.PlaybackBusy, StallDecision.Cancel)
    }

    @Test fun stallDecision_speakingModelActivityOnlyWhenDeadAir() {
        val speaking = ConversationState(phase = Phase.SPEAKING)
        assertStall(speaking, Event.ModelActivity, StallDecision.Arm)
        assertStall(speaking.copy(playbackIdle = false), Event.ModelActivity, StallDecision.None)
        assertStall(speaking.copy(toolsInFlight = setOf("c1")), Event.ModelActivity, StallDecision.None)
    }

    @Test fun stallDecision_speakingPlaybackIdle() {
        val speaking = ConversationState(phase = Phase.SPEAKING)
        // Drain without turnComplete → Arm
        assertStall(speaking.copy(playbackIdle = false), Event.PlaybackIdle, StallDecision.Arm)
        // Drain with turnComplete → Cancel (turn ends)
        assertStall(
            speaking.copy(playbackIdle = false, turnComplete = true),
            Event.PlaybackIdle,
            StallDecision.Cancel,
        )
        // Tools still in flight → None
        assertStall(
            speaking.copy(playbackIdle = false, toolsInFlight = setOf("c1")),
            Event.PlaybackIdle,
            StallDecision.None,
        )
        // turnComplete but tools still running → None (endOfTurn waits)
        assertStall(
            speaking.copy(playbackIdle = false, turnComplete = true, toolsInFlight = setOf("c1")),
            Event.PlaybackIdle,
            StallDecision.None,
        )
    }

    @Test fun stallDecision_speakingTurnComplete() {
        val speaking = ConversationState(phase = Phase.SPEAKING)
        assertStall(speaking, Event.TurnComplete, StallDecision.Cancel) // idle + no tools → end
        assertStall(speaking.copy(playbackIdle = false), Event.TurnComplete, StallDecision.None)
        assertStall(speaking.copy(toolsInFlight = setOf("c1")), Event.TurnComplete, StallDecision.None)
    }

    @Test fun stallDecision_speakingInterruptedAlwaysArms() {
        assertStall(ConversationState(phase = Phase.SPEAKING), Event.Interrupted, StallDecision.Arm)
        assertStall(
            ConversationState(phase = Phase.SPEAKING, playbackIdle = false, toolsInFlight = setOf("c1")),
            Event.Interrupted,
            StallDecision.Arm,
        )
    }

    @Test fun stallDecision_speakingStallTimeout() {
        assertStall(ConversationState(phase = Phase.SPEAKING), Event.StallTimeout, StallDecision.Cancel)
        assertStall(
            ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1")),
            Event.StallTimeout,
            StallDecision.None,
        )
    }

    @Test fun stallDecision_speakingToolCallCancels() {
        assertStall(
            ConversationState(phase = Phase.SPEAKING),
            Event.ToolCallReceived(listOf(fc("c1"))),
            StallDecision.Cancel,
        )
    }

    @Test fun stallDecision_speakingToolDrainArmsWhenIdle() {
        val withTool = ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1"))
        assertStall(withTool, Event.ToolResultsReady(listOf(tr("c1"))), StallDecision.Arm)
        assertStall(withTool, Event.ToolCancelled(listOf("c1")), StallDecision.Arm)
        // Turn already complete: sent results still owe an answer → Arm; a cancel ends the turn → Cancel
        assertStall(
            withTool.copy(turnComplete = true),
            Event.ToolResultsReady(listOf(tr("c1"))),
            StallDecision.Arm,
        )
        assertStall(withTool.copy(turnComplete = true), Event.ToolCancelled(listOf("c1")), StallDecision.Cancel)
        // Still playing → None
        assertStall(
            withTool.copy(playbackIdle = false),
            Event.ToolResultsReady(listOf(tr("c1"))),
            StallDecision.None,
        )
        // Partial drain → None
        assertStall(
            ConversationState(phase = Phase.SPEAKING, toolsInFlight = setOf("c1", "c2")),
            Event.ToolResultsReady(listOf(tr("c1"))),
            StallDecision.None,
        )
        // Already empty (stale results) → None
        assertStall(
            ConversationState(phase = Phase.SPEAKING),
            Event.ToolResultsReady(listOf(tr("c1"))),
            StallDecision.None,
        )
    }

    @Test fun stallDecision_speakingDisconnectedIsNone() {
        assertStall(ConversationState(phase = Phase.SPEAKING), Event.Disconnected, StallDecision.None)
    }
}
