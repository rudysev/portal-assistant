package com.portal.assistant.gemini

import com.portal.assistant.conversation.backend.BackendConfig

/**
 * Hear a Speaker before choosing it (Settings → Voice): tapping a row opens a short Gemini Live session with the
 * selected model and that Speaker (null = Server default), asks Jarvis to introduce itself in one sentence, and
 * plays the answer. A preview that plays to the end is kept for the rest of the dialog, so re-tapping that row
 * replays it with no session (see [State.cached]). This is the pure core — [reduce] decides every transition and [SpeakerPreviewPlayer] is the
 * thin I/O shell that carries out its [Action]s (same pure-core + thin-shell split as the conversation reducer).
 */
object SpeakerPreview {

    /** The preview session's whole system instruction — just enough to sound like Jarvis, nothing else. */
    const val SYSTEM_INSTRUCTION =
        "You are Jarvis, a friendly voice assistant. Answer in one short spoken sentence."

    /** The one text turn a preview sends. The wording of the answer varies run to run; that's accepted. */
    const val INTRO_TEXT = "Introduce yourself in one short sentence."

    /**
     * The session config for one preview: [apiKey] as the credential, the selected [model], the candidate
     * [speaker] (null = Server default), [SYSTEM_INSTRUCTION], and no function declarations (the client's
     * `googleSearch` grounding stays — harmless). [SpeakerPreviewPlayer] gets its session from
     * [GeminiBackend.Factory], the same path as a conversation, so what the user hears is what they get.
     */
    fun sessionConfig(apiKey: String, model: String, speaker: GeminiSpeaker.Speaker?): BackendConfig = BackendConfig(
        credential = apiKey,
        model = model,
        speaker = speaker?.name,
        systemPrompt = SYSTEM_INSTRUCTION,
    )

    /** How the dialog draws one row. */
    enum class RowStatus { NONE, LOADING, PLAYING, FAILED }

    /** Why previews are unavailable right now, shown once above the rows (rows stay selectable). */
    enum class Hint(val text: String) {
        PAUSED("Previews are paused while Jarvis is talking."),
        NO_KEY("Add your Gemini API key to hear previews."),
    }

    /** What the one preview slot is doing. Each started preview gets a fresh [id] so a stopped one's late
     *  callbacks can't touch its successor. */
    sealed interface Status {
        data object Idle : Status

        /** Connecting / waiting for the first audio. */
        data class Loading(val id: Long, val speaker: GeminiSpeaker.Speaker?) : Status

        /**
         * Audio is arriving / playing. Done = [turnDone] (the model finished, socket closed) **and**
         * [playbackIdle] (the player clocked everything out) — either can come first, like the conversation's
         * race-free turn end. A replay starts with [turnDone] already true — the whole clip is in hand.
         */
        data class Playing(
            val id: Long,
            val speaker: GeminiSpeaker.Speaker?,
            val turnDone: Boolean = false,
            val playbackIdle: Boolean = false,
        ) : Status

        /** The last preview couldn't play — its row says so until the next tap. */
        data class Failed(val speaker: GeminiSpeaker.Speaker?) : Status
    }

    /**
     * The dialog's preview state. [hasKey] is fixed when the dialog opens; [conversationLive] follows the
     * conversation (a conversation always wins over a preview); [lastId] numbers previews. [cached] is the rows
     * (null = Default) whose preview played to completion — the shell holds their audio; an interrupted or
     * failed preview never gets here. It lives as long as the dialog's state, i.e. one dialog open.
     */
    data class State(
        val hasKey: Boolean,
        val conversationLive: Boolean = false,
        val status: Status = Status.Idle,
        val lastId: Long = 0,
        val cached: Set<GeminiSpeaker.Speaker?> = emptySet(),
    ) {
        /** Why a tap won't play, or null when it will. */
        val hint: Hint? get() = when {
            conversationLive -> Hint.PAUSED
            !hasKey -> Hint.NO_KEY
            else -> null
        }

        /** How the row for [speaker] (null = the Default row) shows. */
        fun rowStatus(speaker: GeminiSpeaker.Speaker?): RowStatus = when (val s = status) {
            Status.Idle -> RowStatus.NONE
            is Status.Loading -> if (s.speaker == speaker) RowStatus.LOADING else RowStatus.NONE
            is Status.Playing -> if (s.speaker == speaker) RowStatus.PLAYING else RowStatus.NONE
            is Status.Failed -> if (s.speaker == speaker) RowStatus.FAILED else RowStatus.NONE
        }
    }

    sealed interface Event {
        /** The user tapped a row (null = the Default row). */
        data class Tap(val speaker: GeminiSpeaker.Speaker?) : Event

        /** Preview [id]'s session finished setup and can take the text turn. */
        data class Ready(val id: Long) : Event

        /** Preview [id] received a chunk of speech (already queued on its player). */
        data class Audio(val id: Long) : Event

        /** Preview [id]'s model finished its answer. */
        data class TurnComplete(val id: Long) : Event

        /** Preview [id]'s player ran out of queued audio (end of answer, or a mid-stream underrun). */
        data class PlaybackDrained(val id: Long) : Event

        /** Preview [id]'s socket failed or closed (offline, refused, dropped). */
        data class Disconnected(val id: Long) : Event

        /** A conversation went live ([live]) or back to idle. */
        data class ConversationLive(val live: Boolean) : Event

        /** The dialog closed — Cancel, back, an outside tap, or Use this voice. */
        data object Dismissed : Event
    }

    sealed interface Action {
        /** Open preview [id]'s Live session with [speaker] and start its player. */
        data class Connect(val id: Long, val speaker: GeminiSpeaker.Speaker?) : Action

        /** Play [speaker]'s kept clip as preview [id] — a fresh player, no session. */
        data class Replay(val id: Long, val speaker: GeminiSpeaker.Speaker?) : Action

        /** Preview [id] played to completion — keep its audio as [speaker]'s clip. Comes before its [Stop]. */
        data class KeepClip(val id: Long, val speaker: GeminiSpeaker.Speaker?) : Action

        /** Send preview [id]'s one text turn — [INTRO_TEXT]. */
        data class SendIntro(val id: Long) : Action

        /** Close preview [id]'s socket — the answer is in; its queued audio keeps playing. */
        data class CloseSession(val id: Long) : Action

        /** Stop preview [id]'s player and close its socket. Idempotent in the shell. */
        data class Stop(val id: Long) : Action
    }

    data class Decision(val state: State, val actions: List<Action> = emptyList())

    fun reduce(state: State, event: Event): Decision = when (event) {
        // One preview at a time: a tap stops whatever is running, then starts the tapped row's — replaying its
        // kept clip when there is one, else connecting.
        is Event.Tap -> if (state.hint != null) {
            Decision(state.copy(status = Status.Idle), stopRunning(state)) // selection only — no connection
        } else {
            val id = state.lastId + 1
            if (event.speaker in state.cached) {
                Decision(
                    state.copy(status = Status.Playing(id, event.speaker, turnDone = true), lastId = id),
                    stopRunning(state) + Action.Replay(id, event.speaker),
                )
            } else {
                Decision(
                    state.copy(status = Status.Loading(id, event.speaker), lastId = id),
                    stopRunning(state) + Action.Connect(id, event.speaker),
                )
            }
        }

        is Event.Ready -> when (val s = state.status) {
            is Status.Loading -> if (s.id == event.id) Decision(state, listOf(Action.SendIntro(s.id))) else Decision(state)
            else -> Decision(state)
        }

        is Event.Audio -> when (val s = state.status) {
            is Status.Loading ->
                if (s.id == event.id) Decision(state.copy(status = Status.Playing(s.id, s.speaker))) else Decision(state)
            is Status.Playing ->
                if (s.id == event.id) Decision(state.copy(status = s.copy(playbackIdle = false))) else Decision(state)
            else -> Decision(state)
        }

        is Event.TurnComplete -> when (val s = state.status) {
            // The model answered without a single audio chunk — nothing to hear.
            is Status.Loading -> if (s.id == event.id) fail(state, s.id, s.speaker) else Decision(state)
            is Status.Playing -> when {
                s.id != event.id -> Decision(state)
                s.playbackIdle -> finish(state, s)
                else -> Decision(state.copy(status = s.copy(turnDone = true)), listOf(Action.CloseSession(s.id)))
            }
            else -> Decision(state)
        }

        is Event.PlaybackDrained -> when (val s = state.status) {
            is Status.Playing -> when {
                s.id != event.id -> Decision(state)
                s.turnDone -> finish(state, s)
                else -> Decision(state.copy(status = s.copy(playbackIdle = true)))
            }
            else -> Decision(state)
        }

        is Event.Disconnected -> when (val s = state.status) {
            is Status.Loading -> if (s.id == event.id) fail(state, s.id, s.speaker) else Decision(state)
            // After turnComplete the close is our own CloseSession — the audio is already in hand.
            is Status.Playing -> if (s.id == event.id && !s.turnDone) fail(state, s.id, s.speaker) else Decision(state)
            else -> Decision(state)
        }

        // A conversation always wins: going live stops any preview on the spot and pauses taps until it ends.
        is Event.ConversationLive -> when {
            event.live == state.conversationLive -> Decision(state)
            else -> Decision(state.copy(conversationLive = event.live, status = Status.Idle), stopRunning(state))
        }

        is Event.Dismissed -> Decision(state.copy(status = Status.Idle), stopRunning(state))
    }

    /** [Action.Stop] for the preview in flight, if any. */
    private fun stopRunning(state: State): List<Action> = when (val s = state.status) {
        is Status.Loading -> listOf(Action.Stop(s.id))
        is Status.Playing -> listOf(Action.Stop(s.id))
        else -> emptyList()
    }

    /** Preview [id] couldn't play — stop it and flag [speaker]'s row. */
    private fun fail(state: State, id: Long, speaker: GeminiSpeaker.Speaker?) =
        Decision(state.copy(status = Status.Failed(speaker)), listOf(Action.Stop(id)))

    /** Preview [p] played to the end — keep its clip (unless it was already a replay) and free the slot. */
    private fun finish(state: State, p: Status.Playing): Decision =
        if (p.speaker in state.cached) {
            Decision(state.copy(status = Status.Idle), listOf(Action.Stop(p.id)))
        } else {
            Decision(
                state.copy(status = Status.Idle, cached = state.cached + p.speaker),
                listOf(Action.KeepClip(p.id, p.speaker), Action.Stop(p.id)),
            )
        }
}
