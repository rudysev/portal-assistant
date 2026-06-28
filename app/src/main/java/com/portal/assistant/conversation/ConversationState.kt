package com.portal.assistant.conversation

import com.portal.assistant.gemini.FunctionCall
import com.portal.assistant.gemini.ToolResult

/**
 * The conversation as a **pure state machine** — `reduce(state, event) -> (state, actions)` with no
 * Android, no I/O, no threads — so the tricky event orderings are exhaustively unit-testable (the same
 * pattern as portal-wake's `WakeMatcher` / `HandoffRecovery`). The impure [AssistantEngine] runs this
 * on one Handler thread: every callback (LiveClient, PcmPlayer, timers) becomes an [Event] posted to
 * that thread, so there are no locks and no races — which is what lets us delete the reference app's
 * CAS latch.
 *
 * Phases: [Phase.CONNECTING] (opening the socket) → [Phase.LISTENING] (mic streaming to the model) →
 * [Phase.SPEAKING] (model's audio playing, mic muted = half-duplex) → back to LISTENING (multi-turn)
 * or [Phase.ENDED] (release the mic; portal-wake reclaims by detection). ENDED is terminal.
 *
 * **Race-free turn end.** A turn is over only when BOTH the server said `turnComplete` AND the player
 * has drained AND all tool calls have received responses ([ConversationState.turnComplete] &&
 * [ConversationState.playbackIdle] && [ConversationState.toolsInFlight].isEmpty()).
 *
 * **Two timers, both justified** (see [AssistantEngine] for the durations):
 *  - **No-speech** — re-armed on each [Event.UserSpeaking], fires [Event.NoSpeechTimeout] after silence;
 *    the only way to release the mic when the user walks away.
 *  - **Stall** — a **dead-air** clock: armed when the model takes the turn (pre-first-audio gap) or when
 *    playback drains without a `turnComplete`; **cancelled while audio is playing** or while tool calls
 *    are in flight. Fires [Event.StallTimeout] only when nothing is playing, no tools are running, and
 *    the turn isn't done — genuine dead air that would otherwise wedge us in SPEAKING.
 */
enum class Phase { CONNECTING, LISTENING, SPEAKING, ENDED }

/** Things that happen, fed to [reduce]. Sources noted in [AssistantEngine]. */
sealed interface Event {
    /** Socket setup complete — ready to stream. */
    data object Ready : Event

    /** The server transcribed some user speech (proxy for "the user is talking"). */
    data object UserSpeaking : Event

    /** The model took the turn (first `modelTurn` content, possibly before any audio). */
    data object ModelStarted : Event

    /** An audio chunk arrived from the model (progress; cancels the dead-air stall watchdog). */
    data object PlaybackBusy : Event

    /** The player's queue drained (this turn's audio finished playing). */
    data object PlaybackIdle : Event

    /** The server marked the model's turn complete. */
    data object TurnComplete : Event

    /** The server interrupted/revised the current turn. */
    data object Interrupted : Event

    /** The no-speech timer fired (user has been silent). */
    data object NoSpeechTimeout : Event

    /** The stall watchdog fired (model produced no audio/completion in time). */
    data object StallTimeout : Event

    /** The socket failed or closed unexpectedly. */
    data object Disconnected : Event

    /** The model wants to invoke on-device tools. */
    data class ToolCallReceived(val calls: List<FunctionCall>) : Event

    /** Tool execution finished — results are ready to send back to the model. */
    data class ToolResultsReady(val results: List<ToolResult>) : Event

    /** The server cancelled tool calls with the given ids. */
    data class ToolCancelled(val ids: List<String>) : Event
}

/** A side effect for [AssistantEngine] to execute. Pure data — the reducer performs none of these. */
sealed interface Action {
    /** Route mic frames to the server and show the recording bar. */
    data object StartForwarding : Action

    /** Stop sending mic frames (half-duplex mute) and hide the bar. */
    data object StopForwarding : Action

    /** (Re)arm the no-speech / walk-away timer. */
    data object ArmNoSpeechTimer : Action

    /** Cancel the no-speech timer. */
    data object CancelNoSpeechTimer : Action

    /** (Re)arm the stall watchdog. */
    data object ArmStallTimer : Action

    /** Cancel the stall watchdog. */
    data object CancelStallTimer : Action

    /** Drop any audio not yet played (the model was interrupted). */
    data object FlushPlayback : Action

    /** Apply side effects deferred until the assistant finished speaking (mute, DND-enable). See [AfterSpeech]. */
    data object FireAfterSpeech : Action

    /** End the conversation: close the socket, release the mic, stop the service. */
    data object End : Action

    /** Invoke the listed tools on the tool executor. */
    data class ExecuteTools(val calls: List<FunctionCall>) : Action

    /** Send the tool results back to the model via the Live socket. */
    data class SendToolResponse(val results: List<ToolResult>) : Action
}

/**
 * @param turnComplete the server has marked the current model turn complete.
 * @param playbackIdle the player has no audio left to play (true when no audio has been produced yet).
 * @param toolsInFlight function-call IDs currently executing on the tool executor. Turn-end and stall
 *   are gated on this being empty — the model must receive all results before the turn can close.
 */
data class ConversationState(
    val phase: Phase = Phase.CONNECTING,
    val turnComplete: Boolean = false,
    val playbackIdle: Boolean = true,
    val toolsInFlight: Set<String> = emptySet(),
)

/** The result of [reduce]: the next state and the side effects to run. */
data class Decision(val state: ConversationState, val actions: List<Action>)

/**
 * The transition function. [multiTurn] = false ends after the first answer (Phase 2.a); true re-opens
 * the mic for a follow-up (Phase 2.b). Unknown/out-of-phase events are ignored (no change).
 */
fun reduce(state: ConversationState, event: Event, multiTurn: Boolean): Decision = when (state.phase) {
    Phase.ENDED -> Decision(state, emptyList())

    Phase.CONNECTING -> when (event) {
        Event.Ready -> Decision(
            ConversationState(phase = Phase.LISTENING),
            listOf(Action.StartForwarding, Action.ArmNoSpeechTimer),
        )

        Event.Disconnected -> ended()

        else -> unchanged(state)
    }

    Phase.LISTENING -> when (event) {
        Event.UserSpeaking -> Decision(state, listOf(Action.ArmNoSpeechTimer))

        // re-arm on speech
        Event.ModelStarted -> Decision(
            ConversationState(phase = Phase.SPEAKING),
            listOf(Action.StopForwarding, Action.CancelNoSpeechTimer, Action.ArmStallTimer),
        )

        is Event.ToolCallReceived -> {
            // Model took the turn via a tool call (no audio yet). Same mic mute as ModelStarted.
            // Arm stall immediately — without it, a hung tool has no watchdog until results arrive.
            val newIds = state.toolsInFlight + event.calls.map { it.id }
            Decision(
                ConversationState(phase = Phase.SPEAKING, toolsInFlight = newIds),
                listOf(Action.StopForwarding, Action.CancelNoSpeechTimer, Action.ArmStallTimer, Action.ExecuteTools(event.calls)),
            )
        }

        // NoSpeechTimeout ends the conversation. NOTE: this is NOT purely server-driven — before dispatching
        // it, AssistantEngine applies a bounded local-energy *grace* (NoSpeechGrace) that defers the timeout
        // while the mic still hears the user, so a follow-up started late in the window isn't dropped before
        // its transcript arrives. Don't "simplify" on the assumption that only a server transcript keeps the
        // mic open — that grace is deliberate (and capped so noise can't pin the mic).
        Event.NoSpeechTimeout, Event.Disconnected -> ended()

        else -> unchanged(state)
    }

    Phase.SPEAKING -> when (event) {
        // Audio is flowing → cancel the stall (it measures dead-air, armed only when nothing is playing).
        Event.PlaybackBusy -> Decision(state.copy(playbackIdle = false), listOf(Action.CancelStallTimer))

        Event.PlaybackIdle -> {
            val drained = state.copy(playbackIdle = true)
            // Both conditions met → end/re-listen. Drained but the server hasn't said turnComplete → start
            // the dead-air clock (truncated audio, or a gap before more arrives).
            if (drained.turnComplete) endOfTurn(drained, multiTurn) else Decision(drained, listOf(Action.ArmStallTimer))
        }

        Event.TurnComplete -> endOfTurn(state.copy(turnComplete = true), multiTurn)

        Event.Interrupted -> Decision(
            state.copy(turnComplete = false, playbackIdle = true, toolsInFlight = emptySet()),
            listOf(Action.FlushPlayback, Action.ArmStallTimer),
        )

        // Stall is gated: ignore while tools are in flight (the model is waiting for results, not dead).
        Event.StallTimeout -> if (state.toolsInFlight.isNotEmpty()) unchanged(state) else stalledTurnEnd(multiTurn)

        Event.Disconnected -> ended()

        is Event.ToolCallReceived -> {
            // Additional tool calls mid-turn (chained rounds or parallel calls).
            val newIds = state.toolsInFlight + event.calls.map { it.id }
            Decision(
                state.copy(toolsInFlight = newIds),
                listOf(Action.CancelStallTimer, Action.ExecuteTools(event.calls)),
            )
        }

        is Event.ToolResultsReady -> {
            val inFlight = state.toolsInFlight
            // Only send results still in flight — drop any whose IDs were already removed by an
            // Interrupted or ToolCancelled (e.g. a future that finished just after cancel(true)).
            val validResults = event.results.filter { it.id in inFlight }
            val remaining = inFlight - event.results.map { it.id }.toSet()
            val actions = mutableListOf<Action>()
            if (validResults.isNotEmpty()) actions += Action.SendToolResponse(validResults)
            // Arm stall only when we actually drained something — not on a fully-stale arrival.
            if (remaining.isEmpty() && inFlight.isNotEmpty()) actions += Action.ArmStallTimer
            Decision(state.copy(toolsInFlight = remaining), actions)
        }

        is Event.ToolCancelled -> {
            val remaining = state.toolsInFlight - event.ids.toSet()
            val actions = mutableListOf<Action>()
            if (remaining.isEmpty() && state.toolsInFlight.isNotEmpty()) actions += Action.ArmStallTimer
            Decision(state.copy(toolsInFlight = remaining), actions)
        }

        else -> unchanged(state)
    }
}

/**
 * A stalled turn (no audio/completion in time): flush the partial audio, then re-listen or end.
 * [Action.FireAfterSpeech] applies any deferred mute/DND — a wedged turn still honours the request (the
 * confirmation already drained, since the stall only arms while nothing is playing).
 */
private fun stalledTurnEnd(multiTurn: Boolean): Decision = if (multiTurn) {
    Decision(
        ConversationState(phase = Phase.LISTENING),
        listOf(Action.FireAfterSpeech, Action.FlushPlayback, Action.CancelStallTimer, Action.StartForwarding, Action.ArmNoSpeechTimer),
    )
} else {
    Decision(ConversationState(phase = Phase.ENDED), listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.End))
}

/**
 * Finish the turn once all three conditions hold: turnComplete, playbackIdle, no tools in flight.
 * Re-listen (multi-turn) or end. Otherwise keep waiting. [Action.FireAfterSpeech] is first so a deferred
 * mute/DND applies the instant the confirmation finished playing — and, on the End path, before teardown.
 */
private fun endOfTurn(state: ConversationState, multiTurn: Boolean): Decision {
    if (!(state.turnComplete && state.playbackIdle && state.toolsInFlight.isEmpty())) return Decision(state, emptyList())
    return if (multiTurn) {
        Decision(
            ConversationState(phase = Phase.LISTENING),
            listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.StartForwarding, Action.ArmNoSpeechTimer),
        )
    } else {
        Decision(ConversationState(phase = Phase.ENDED), listOf(Action.FireAfterSpeech, Action.CancelStallTimer, Action.End))
    }
}

private fun ended(): Decision = Decision(ConversationState(phase = Phase.ENDED), listOf(Action.End))

private fun unchanged(state: ConversationState): Decision = Decision(state, emptyList())
