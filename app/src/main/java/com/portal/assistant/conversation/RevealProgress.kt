package com.portal.assistant.conversation

import kotlin.math.roundToInt

/**
 * Pure paced-reveal helpers. The model's transcription text arrives ahead of its 24 kHz speech, so the UI
 * reveals only the words whose audio has actually *played*. [RevealTracker] owns the stateful played/received
 * byte counts and calls these helpers; the engine orchestrates and stamps the result on the latest model
 * [Turn]; the UI renders it.
 *
 * No Android/Compose — fully unit-tested (the same pure-logic pattern as [ConversationState] / [Transcript]).
 */
object RevealProgress {

    /** Index ranges (inclusive) of each whitespace-separated word within [text], in order — for slicing. */
    fun wordRanges(text: String): List<IntRange> = NON_WHITESPACE.findAll(text).map { it.range }.toList()

    /** Number of whitespace-separated words in [text]. */
    fun wordCount(text: String): Int = NON_WHITESPACE.findAll(text).count()

    /**
     * How many words to show: the answer's words scaled by the fraction of its received audio that has
     * **played** ([playedBytes] ÷ [receivedBytes]), **monotonically** non-decreasing (never below
     * [previous], so a fraction dip when the denominator grows can't un-reveal a word) and capped at
     * [totalWords]. Because the server co-streams text and audio, byte-fraction ≈ word-fraction — an
     * approximation, but it tracks the voice with no rate constant. `receivedBytes <= 0` → keep [previous].
     */
    fun wordsToShow(totalWords: Int, playedBytes: Long, receivedBytes: Long, previous: Int): Int {
        if (receivedBytes <= 0L) return previous
        val played = minOf(playedBytes, receivedBytes) // played can briefly run ahead of received (cross-thread)
        val target = (totalWords * (played.toFloat() / receivedBytes)).roundToInt()
        return minOf(totalWords, maxOf(previous, target))
    }

    private val NON_WHITESPACE = Regex("\\S+")
}
