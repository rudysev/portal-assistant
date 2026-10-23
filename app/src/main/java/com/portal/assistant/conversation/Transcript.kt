package com.portal.assistant.conversation

/**
 * The conversation's turns as shown on screen, and the paced reveal of Jarvis's answer: a pure, immutable value
 * the engine drives with [Event]s ([on]) and publishes ([turns]). No Android, never reads the player or a clock —
 * played audio arrives as a value in the events — so every rule is tested by replaying event sequences (the same
 * pure-logic pattern as [ConversationState] / [MicGate]).
 *
 * Turn boundaries are explicit, never inferred: [Event.ListeningStarted] arms a fresh user turn and a fresh Jarvis
 * turn, [Event.Interrupted] a fresh Jarvis turn. Text grows the active turn of its role, creating it lazily on the
 * first non-empty delta (so no empty bubble appears before any words), whether Jarvis's text or audio comes first.
 *
 * **Paced reveal.** Jarvis's transcription arrives ahead of its speech, so its active turn carries
 * [Turn.revealedWords]: its words scaled by the turn's audio played ÷ received ([RevealProgress.wordsToShow] —
 * monotonic, capped). The reveal restarts at the two boundaries above, re-based on the played-bytes count then:
 * a turn ends only once its audio has played (and a stalled turn is flushed before listening restarts), so that
 * count is exactly where the next turn's audio begins. Only the active Jarvis turn carries a count; a finished
 * turn shows its full text, or — when it ended early with audio unplayed — only the words heard.
 */
data class Transcript(
    val turns: List<Turn> = emptyList(),
    private val userTurnId: Long = NONE,
    private val jarvisTurnId: Long = NONE,
    // Raw, un-stripped text of the active Jarvis turn. Jarvis streams markdown (**bold**, "1." lists); we keep the
    // raw accumulation here and store the markdown-stripped form in Turn.text, re-stripping the FULL raw on each
    // delta so a marker split across deltas (e.g. "*" then "*") still strips correctly.
    private val jarvisRaw: String = "",
    /** Played bytes when the reveal last restarted, so played audio is measured per turn. */
    private val revealBaseBytes: Long = 0L,
    /** Audio bytes received for the active Jarvis turn — the denominator of the reveal fraction. */
    private val receivedBytes: Long = 0L,
    private val revealedWords: Int = 0,
) {
    /** What happens to the transcript; [AssistantEngine] sends these. `playedBytes` is the player's running count. */
    sealed interface Event {
        /** Listening (re)starts after Jarvis's turn: fresh user and Jarvis turns; the reveal restarts. */
        data class ListeningStarted(val playedBytes: Long) : Event

        /** The voice backend interrupted Jarvis's turn: a fresh Jarvis turn; the reveal restarts. */
        data class Interrupted(val playedBytes: Long) : Event

        /** A delta of the user's words (the server's input transcription, or a tapped suggestion's text). */
        data class UserText(val delta: String) : Event

        /**
         * A delta of Jarvis's words, applied only while [phase] is SPEAKING — a belated frame after listening
         * restarted would otherwise open a stray bubble for the previous answer.
         */
        data class JarvisText(val delta: String, val phase: Phase) : Event

        /** A chunk of Jarvis's audio arrived ([bytes] of it). */
        data class AudioReceived(val bytes: Int, val playedBytes: Long) : Event

        /** The player played more audio. */
        data class AudioPlayed(val playedBytes: Long) : Event
    }

    /**
     * The transcript after [event]. Returns `this` when nothing changed; when only the reveal's bookkeeping
     * changed (audio received, no new word shown), the result shares this one's [turns] list — so the engine
     * publishes only when `turns` is a new list.
     */
    fun on(event: Event): Transcript = when (event) {
        is Event.ListeningStarted -> restartReveal(event.playedBytes).copy(userTurnId = NONE)

        is Event.Interrupted -> restartReveal(event.playedBytes)

        is Event.UserText -> appendUser(event.delta)

        is Event.JarvisText -> if (event.phase == Phase.SPEAKING) appendJarvis(event.delta) else this

        is Event.AudioReceived -> if (event.bytes <= 0) {
            this
        } else {
            copy(receivedBytes = receivedBytes + event.bytes).revealAt(event.playedBytes)
        }

        is Event.AudioPlayed -> revealAt(event.playedBytes)
    }

    /**
     * Arm a fresh Jarvis turn and restart the reveal from [playedBytes]. The ending turn shows its full text if
     * all of its audio played, or is cut at the words heard if some never did (an interruption flushes it) —
     * dropped if none were.
     */
    private fun restartReveal(playedBytes: Long): Transcript {
        val idx = jarvisIndex()
        val endingTurn = if (idx < 0) null else endedAsHeard(turns[idx], playedBytes)
        return copy(
            turns = when {
                idx < 0 -> turns
                endingTurn == null -> turns.filterIndexed { i, _ -> i != idx }
                else -> turns.patched(idx) { endingTurn }
            },
            jarvisTurnId = NONE,
            jarvisRaw = "",
            revealBaseBytes = playedBytes,
            receivedBytes = 0L,
            revealedWords = 0,
        )
    }

    /** Recompute the active Jarvis turn's reveal from [playedBytes]; `this` when no new word shows. */
    private fun revealAt(playedBytes: Long): Transcript {
        val idx = jarvisIndex()
        val next = if (idx < 0) revealedWords else wordsHeard(turns[idx].text, playedBytes)
        return if (next == revealedWords) this else copy(turns = turns.patched(idx) { it.copy(revealedWords = next) }, revealedWords = next)
    }

    /**
     * The active Jarvis [turn] as it ends at [playedBytes]: its full text, or only the words heard if cut short
     * (`null` when no word was). A turn with no audio keeps its text — there's nothing to measure it against.
     */
    private fun endedAsHeard(turn: Turn, playedBytes: Long): Turn? {
        val heard = wordsHeard(turn.text, playedBytes)
        return when {
            playedBytes - revealBaseBytes >= receivedBytes -> turn.copy(revealedWords = null)

            heard == 0 -> null

            else -> {
                val words = RevealProgress.wordRanges(turn.text)
                turn.copy(text = turn.text.substring(0, words[heard - 1].last + 1), revealedWords = null)
            }
        }
    }

    /** How many of [text]'s words the active turn's audio played by [playedBytes] covers (monotonic, capped). */
    private fun wordsHeard(text: String, playedBytes: Long): Int = RevealProgress.wordsToShow(
        RevealProgress.wordCount(text),
        playedBytes - revealBaseBytes,
        receivedBytes,
        revealedWords,
    )

    private fun appendUser(delta: String): Transcript {
        val idx = if (userTurnId == NONE) -1 else turns.indexOfLast { it.id == userTurnId }
        return when {
            delta.isEmpty() -> this
            idx < 0 -> Turn(Role.USER, delta).let { turn -> copy(turns = turns + turn, userTurnId = turn.id) }
            else -> copy(turns = turns.patched(idx) { it.copy(text = it.text + delta) })
        }
    }

    /**
     * Append a Jarvis text delta. The turn's display text is the markdown-[Markdown.strip]ped accumulation — the
     * answer is spoken, so the transcript reads as plain prose, not raw markup. Created lazily on the first delta
     * that survives stripping (so leading markup alone doesn't open an empty bubble).
     */
    private fun appendJarvis(delta: String): Transcript {
        if (delta.isEmpty()) return this
        val raw = jarvisRaw + delta
        val clean = Markdown.strip(raw)
        val idx = jarvisIndex()
        return when {
            idx >= 0 -> copy(turns = turns.patched(idx) { it.copy(text = clean) }, jarvisRaw = raw)

            // clean is the full stripped accumulation, not a delta
            clean.isEmpty() -> copy(jarvisRaw = raw)

            // only markup so far → keep accumulating
            else -> Turn(Role.MODEL, clean, revealedWords = revealedWords)
                .let { turn -> copy(turns = turns + turn, jarvisTurnId = turn.id, jarvisRaw = raw) }
        }
    }

    private fun jarvisIndex(): Int = if (jarvisTurnId == NONE) -1 else turns.indexOfLast { it.id == jarvisTurnId }

    private fun List<Turn>.patched(idx: Int, change: (Turn) -> Turn): List<Turn> = toMutableList().also { it[idx] = change(it[idx]) }

    private companion object {
        const val NONE = -1L
    }
}
