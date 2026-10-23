package com.portal.assistant.conversation

/**
 * The conversation as a **pure state machine** — `reduce(state, event) -> (state, actions)` with no
 * Android, no I/O, no threads — so the tricky event orderings are exhaustively unit-testable (the same
 * pattern as portal-wake's `WakeMatcher` / `HandoffRecovery`). The impure [AssistantEngine] runs this
 * on one Handler thread: every callback (backend, PcmPlayer, timers) becomes an [Event] posted to
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
 *  - **Stall** — a **dead-air** clock. Policy is entirely in [stallDecision] (Arm / Cancel / None);
 *    [reduce] appends the matching action after each transition. The engine only runs the Handler
 *    timer. Never arm while audio is queued/playing — that reintroduces the mid-sentence cut-off.
 *    Fires [Event.StallTimeout] only for genuine dead air that would otherwise wedge us in SPEAKING
 *    ([Event.StallTimeout] is also ignored while tools are in flight).
 */
enum class Phase { CONNECTING, LISTENING, SPEAKING, ENDED }

/** Things that happen, fed to [reduce]. Sources noted in [AssistantEngine]. */
sealed interface Event {
    /** Socket setup complete — ready to stream. */
    data object Ready : Event

    /** The server transcribed some user speech (proxy for "the user is talking"). */
    data object UserSpeaking : Event

    /**
     * Model-side activity proving the turn is not wedged (generating, tool pass, output transcript
     * while already speaking). LISTENING → enter SPEAKING (and arm the dead-air stall) — the engine
     * posts this enter path from model-generating / tool calls only, never from a standalone
     * output-transcript frame (it posts one only while already SPEAKING); SPEAKING → re-arm only while
     * playback is idle and no tools are in flight (see [stallDecision]).
     */
    data object ModelActivity : Event

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

    /**
     * Apply side effects deferred until the assistant finished speaking (mute, DND-enable). See
     * [com.portal.assistant.conversation.tools.AfterSpeech].
     */
    data object FireAfterSpeech : Action

    /** End the conversation: close the socket, release the mic, stop the service. */
    data object End : Action

    /** Invoke the listed tools on the tool executor. */
    data class ExecuteTools(val calls: List<FunctionCall>) : Action

    /** Send the tool results back to the model via the backend. */
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
 * Stall Arm/Cancel come only from [stallDecision], appended after the phase transition's actions.
 */
fun reduce(state: ConversationState, event: Event, multiTurn: Boolean): Decision {
    val body = reduceBody(state, event, multiTurn)
    val decision = stallDecision(state, event)
    // Action.End tears down the engine (clears the timer); a trailing CancelStallTimer would be a no-op.
    if (decision == StallDecision.Cancel && Action.End in body.actions) return body
    val stall = stallActions(decision)
    return if (stall.isEmpty()) body else body.copy(actions = body.actions + stall)
}

/** Phase transitions and non-stall side effects. Stall actions are appended by [reduce]. */
private fun reduceBody(state: ConversationState, event: Event, multiTurn: Boolean): Decision = when (state.phase) {
    Phase.ENDED -> Decision(state, emptyList())
    Phase.CONNECTING -> connectingBody(state, event)
    Phase.LISTENING -> listeningBody(state, event)
    Phase.SPEAKING -> speakingBody(state, event, multiTurn)
}

private fun connectingBody(state: ConversationState, event: Event): Decision = when (event) {
    Event.Ready -> Decision(
        ConversationState(phase = Phase.LISTENING),
        listOf(Action.StartForwarding, Action.ArmNoSpeechTimer),
    )

    Event.Disconnected -> ended()

    else -> unchanged(state)
}

private fun listeningBody(state: ConversationState, event: Event): Decision = when (event) {
    Event.UserSpeaking -> Decision(state, listOf(Action.ArmNoSpeechTimer))

    Event.ModelActivity -> Decision(
        ConversationState(phase = Phase.SPEAKING),
        listOf(Action.StopForwarding, Action.CancelNoSpeechTimer),
    )

    is Event.ToolCallReceived -> {
        // Model took the turn via a tool call (no audio yet). Same mic mute as ModelActivity.
        // Stall arm is [stallDecision]'s LISTENING ToolCallReceived row.
        val newIds = state.toolsInFlight + event.calls.map { it.id }
        Decision(
            ConversationState(phase = Phase.SPEAKING, toolsInFlight = newIds),
            listOf(Action.StopForwarding, Action.CancelNoSpeechTimer, Action.ExecuteTools(event.calls)),
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

private fun speakingBody(state: ConversationState, event: Event, multiTurn: Boolean): Decision = when (event) {
    Event.PlaybackBusy -> Decision(state.copy(playbackIdle = false), emptyList())

    Event.ModelActivity -> Decision(state, emptyList())

    Event.PlaybackIdle -> {
        val drained = state.copy(playbackIdle = true)
        // Both conditions met → end/re-listen. Otherwise stay SPEAKING; stall arm/cancel from
        // [stallDecision].
        if (drained.turnComplete) endOfTurn(drained, multiTurn) else Decision(drained, emptyList())
    }

    Event.TurnComplete -> endOfTurn(state.copy(turnComplete = true), multiTurn)

    Event.Interrupted -> {
        // Flush leaves us idle with no tools — always dead air until the model resumes.
        val cleared = state.copy(turnComplete = false, playbackIdle = true, toolsInFlight = emptySet())
        Decision(cleared, listOf(Action.FlushPlayback))
    }

    // Stall is gated: ignore while tools are in flight (the model is waiting for results, not dead).
    Event.StallTimeout -> if (state.toolsInFlight.isNotEmpty()) unchanged(state) else stalledTurnEnd(multiTurn)

    Event.Disconnected -> ended()

    is Event.ToolCallReceived -> {
        // Additional tool calls mid-turn (chained rounds or parallel calls).
        val newIds = state.toolsInFlight + event.calls.map { it.id }
        Decision(
            state.copy(toolsInFlight = newIds),
            listOf(Action.ExecuteTools(event.calls)),
        )
    }

    is Event.ToolResultsReady -> toolResultsBody(state, event, multiTurn)

    is Event.ToolCancelled -> {
        val remaining = state.toolsInFlight - event.ids.toSet()
        endOfTurn(state.copy(toolsInFlight = remaining), multiTurn)
    }

    else -> unchanged(state)
}

private fun toolResultsBody(state: ConversationState, event: Event.ToolResultsReady, multiTurn: Boolean): Decision {
    val inFlight = state.toolsInFlight
    // Only send results still in flight — drop any whose IDs were already removed by an
    // Interrupted or ToolCancelled (e.g. a future that finished just after cancel(true)).
    val validResults = event.results.filter { it.id in inFlight }
    val remaining = inFlight - event.results.map { it.id }.toSet()
    val next = state.copy(toolsInFlight = remaining)
    val send = if (validResults.isNotEmpty()) {
        listOf(Action.SendToolResponse(validResults))
    } else {
        emptyList()
    }
    // If turnComplete + idle were already true, tools were the only blocker — end now.
    val ended = endOfTurn(next, multiTurn)
    return Decision(ended.state, send + ended.actions)
}

/**
 * A stalled turn (no audio/completion in time): flush the partial audio, then re-listen or end.
 * [Action.FireAfterSpeech] applies any deferred mute/DND — a wedged turn still honours the request (the
 * confirmation already drained, since the stall only arms while nothing is playing).
 * Stall cancel is appended by [reduce] via [stallDecision].
 */
private fun stalledTurnEnd(multiTurn: Boolean): Decision = if (multiTurn) {
    Decision(
        ConversationState(phase = Phase.LISTENING),
        listOf(Action.FireAfterSpeech, Action.FlushPlayback, Action.StartForwarding, Action.ArmNoSpeechTimer),
    )
} else {
    Decision(ConversationState(phase = Phase.ENDED), listOf(Action.FireAfterSpeech, Action.End))
}

/**
 * Finish the turn once all three conditions hold: turnComplete, playbackIdle, no tools in flight.
 * Re-listen (multi-turn) or end. Otherwise keep waiting. [Action.FireAfterSpeech] is first so a deferred
 * mute/DND applies the instant the confirmation finished playing — and, on the End path, before teardown.
 * Stall cancel is appended by [reduce] via [stallDecision].
 */
private fun endOfTurn(state: ConversationState, multiTurn: Boolean): Decision {
    if (!(state.turnComplete && state.playbackIdle && state.toolsInFlight.isEmpty())) {
        return Decision(
            state,
            emptyList(),
        )
    }
    return if (multiTurn) {
        Decision(
            ConversationState(phase = Phase.LISTENING),
            listOf(Action.FireAfterSpeech, Action.StartForwarding, Action.ArmNoSpeechTimer),
        )
    } else {
        Decision(ConversationState(phase = Phase.ENDED), listOf(Action.FireAfterSpeech, Action.End))
    }
}

private fun ended(): Decision = Decision(ConversationState(phase = Phase.ENDED), listOf(Action.End))

private fun unchanged(state: ConversationState): Decision = Decision(state, emptyList())
