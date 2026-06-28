package com.portal.assistant.conversation

/**
 * Stateful paced-reveal bookkeeping for the current model turn — the mutable counters the pure
 * [RevealProgress] math needs, kept out of [AssistantEngine] so the engine stays orchestration-only.
 *
 * The reveal tracks the model's transcription against the audio that has actually *played*: words are
 * shown in proportion to [playedBytes] over the turn's received audio bytes, so the text keeps step with
 * the voice with no rate constant. All access is on the engine's single handler thread (no
 * synchronization). [playedBytes] supplies the live `PcmPlayer` played-byte count.
 */
class RevealTracker(private val playedBytes: () -> Long) {

    /** [playedBytes] at this turn's start, so played bytes are measured per turn. */
    private var base = 0L

    /** Audio bytes *received* for this turn — the denominator of the paced-reveal fraction. */
    private var received = 0L

    /** Words revealed so far this turn (monotonic); the engine stamps this on the latest model [Turn]. */
    var revealedWords = 0
        private set

    /** Start a fresh model turn: reveal restarts from 0 and played bytes are re-based to here. */
    fun reset() {
        base = playedBytes()
        received = 0L
        revealedWords = 0
    }

    /** Account audio bytes received for this turn (the reveal-fraction denominator). */
    fun onAudioReceived(bytes: Int) {
        received += bytes
    }

    /**
     * Recompute [revealedWords] from the audio actually played for [modelText]. Returns true only when the
     * count advances — so the caller republishes (and the UI recomposes) only on a real change. No-op
     * before any audio is received or when there is no model text yet.
     */
    fun recompute(modelText: String?): Boolean {
        if (received <= 0L || modelText == null) return false
        val next = RevealProgress.wordsToShow(
            RevealProgress.wordCount(modelText),
            playedBytes() - base,
            received,
            revealedWords,
        )
        if (next == revealedWords) return false
        revealedWords = next
        return true
    }
}
