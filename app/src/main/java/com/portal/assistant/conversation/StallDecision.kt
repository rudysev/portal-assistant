package com.portal.assistant.conversation

/** What the dead-air stall watchdog should do for a (state, event) pair. See [stallDecision]. */
enum class StallDecision { Arm, Cancel, None }

/**
 * Dead-air stall policy for the **pre-transition** [state] and [event]. Single source of truth —
 * [reduce] appends the matching [Action]; no branch hardcodes Arm/Cancel.
 *
 * Invariant: Arm only when we could wedge in SPEAKING with nothing playing; Cancel when audio flows,
 * tools take the turn mid-SPEAKING, or the turn ends. Never arm while audio is queued (mid-sentence
 * cut-off).
 *
 * | Phase | Event | Decision |
 * |---|---|---|
 * | CONNECTING / ENDED | any | None |
 * | LISTENING | ModelActivity | Arm (enter SPEAKING, pre-audio) |
 * | LISTENING | ToolCallReceived | Arm (enter SPEAKING; StallTimeout is ignored while tools run, so this is mostly a
 * no-op until ToolResultsReady re-arms — preserved) |
 * | SPEAKING | PlaybackBusy | Cancel |
 * | SPEAKING | ModelActivity | Arm iff dead air (idle + no tools) |
 * | SPEAKING | PlaybackIdle | Cancel if turn ends; else Arm iff no tools |
 * | SPEAKING | TurnComplete | Cancel iff turn ends (idle + no tools, no sign-off pending) |
 * | SPEAKING | Interrupted | Arm (clears to idle + no tools) |
 * | SPEAKING | StallTimeout | Cancel iff no tools (else ignored) |
 * | SPEAKING | ToolCallReceived | Cancel (tools own the turn) |
 * | SPEAKING | ToolResultsReady / ToolCancelled | Cancel if drain ends the turn; else Arm iff emptied and idle |
 * | SPEAKING | Disconnected | None (engine teardown clears the timer) |
 */
fun stallDecision(state: ConversationState, event: Event): StallDecision = when (state.phase) {
    Phase.ENDED, Phase.CONNECTING -> StallDecision.None

    Phase.LISTENING -> when (event) {
        Event.ModelActivity -> StallDecision.Arm
        is Event.ToolCallReceived -> StallDecision.Arm
        else -> StallDecision.None
    }

    Phase.SPEAKING -> speakingStall(state, event)
}

/** [stallDecision]'s SPEAKING rows. */
private fun speakingStall(state: ConversationState, event: Event): StallDecision {
    val noTools = state.toolsInFlight.isEmpty()
    return when (event) {
        Event.PlaybackBusy -> StallDecision.Cancel

        // Dead air: the model is still talking, yet nothing plays and no tools run.
        Event.ModelActivity -> armIf(nothingInFlight(state))

        // This event sets playbackIdle=true; turn ends when turnComplete && no tools.
        Event.PlaybackIdle -> if (state.turnComplete && noTools) StallDecision.Cancel else armIf(noTools)

        // The model is done, so the turn ends iff nothing plays and no tools run — unless it's the tool-call turn's
        // own turnComplete before the sign-off, which ends nothing (the stall stays armed).
        Event.TurnComplete -> cancelIf(!state.signOffPending && nothingInFlight(state))

        // A barge-in flushes playback and tools (idle + no tools): dead air until the model resumes.
        Event.Interrupted -> StallDecision.Arm

        Event.StallTimeout -> cancelIf(noTools)

        is Event.ToolCallReceived -> StallDecision.Cancel

        is Event.ToolResultsReady -> toolDrainStall(
            state,
            state.toolsInFlight - event.results.map { it.id }.toSet(),
            awaitsSignOff = state.awaitsSignOff(event),
        )

        is Event.ToolCancelled -> toolDrainStall(state, state.toolsInFlight - event.ids.toSet())

        else -> StallDecision.None
    }
}

private fun armIf(condition: Boolean): StallDecision = if (condition) StallDecision.Arm else StallDecision.None

private fun cancelIf(condition: Boolean): StallDecision = if (condition) StallDecision.Cancel else StallDecision.None

/** Stall after a tool drain: end-of-turn → Cancel; still waiting on the model → Arm; else None. */
private fun toolDrainStall(
    state: ConversationState,
    remaining: Set<String>,
    awaitsSignOff: Boolean = false,
): StallDecision = when {
    remaining.isNotEmpty() || state.toolsInFlight.isEmpty() || !state.playbackIdle -> StallDecision.None
    state.turnComplete && !awaitsSignOff -> StallDecision.Cancel
    else -> StallDecision.Arm
}

internal fun stallActions(d: StallDecision): List<Action> = when (d) {
    StallDecision.Arm -> listOf(Action.ArmStallTimer)
    StallDecision.Cancel -> listOf(Action.CancelStallTimer)
    StallDecision.None -> emptyList()
}

/**
 * Nothing playing and no tools running. In SPEAKING that is dead air while the model is still talking (the only
 * condition that may (re)arm the stall), and the turn end once its turn is complete.
 */
private fun nothingInFlight(state: ConversationState): Boolean = state.playbackIdle && state.toolsInFlight.isEmpty()
