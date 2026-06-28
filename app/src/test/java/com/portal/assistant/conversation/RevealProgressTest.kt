package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the paced-reveal helpers. */
class RevealProgressTest {

    // ---- word splitting ----------------------------------------------------------------------------

    @Test fun wordCountCountsWhitespaceSeparatedWords() {
        assertEquals(3, RevealProgress.wordCount("  the   quick\nbrown  "))
        assertEquals(0, RevealProgress.wordCount("   "))
        assertEquals(0, RevealProgress.wordCount(""))
        assertEquals(4, RevealProgress.wordCount("hello, it's 20°C today."))
    }

    @Test fun wordRangesSliceTheOriginalTextVerbatim() {
        // Ranges index the ORIGINAL text (whitespace/newlines preserved) so the reveal renders the model's
        // formatting and the reveal→snap handoff doesn't reflow.
        val text = "hello,\nit's  20°C"
        val sliced = RevealProgress.wordRanges(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("hello,", "it's", "20°C"), sliced)
        assertEquals(emptyList<IntRange>(), RevealProgress.wordRanges("   "))
    }

    // ---- wordsToShow -------------------------------------------------------------------------------

    @Test fun zeroReceivedKeepsPrevious() {
        assertEquals(3, RevealProgress.wordsToShow(totalWords = 10, playedBytes = 999, receivedBytes = 0, previous = 3))
    }

    @Test fun mapsPlayedFractionToWords() {
        assertEquals(0, RevealProgress.wordsToShow(10, playedBytes = 0, receivedBytes = 100, previous = 0))
        assertEquals(5, RevealProgress.wordsToShow(10, playedBytes = 50, receivedBytes = 100, previous = 0))
        assertEquals(10, RevealProgress.wordsToShow(10, playedBytes = 100, receivedBytes = 100, previous = 0))
    }

    @Test fun receiveBurstRevealsProportionallyLittle() {
        // Server streamed ahead of realtime: lots received, little played → few words (tracks the voice).
        assertEquals(1, RevealProgress.wordsToShow(10, playedBytes = 10, receivedBytes = 100, previous = 0))
    }

    @Test fun playedAheadOfReceivedIsCappedNotOverUnity() {
        // Cross-thread: played can briefly exceed received → fraction capped at 1, not >totalWords.
        assertEquals(10, RevealProgress.wordsToShow(10, playedBytes = 130, receivedBytes = 100, previous = 0))
    }

    @Test fun isMonotonicWhenFractionDrops() {
        // The denominator (received) can jump up between calls; reveal must not regress below `previous`.
        assertEquals(5, RevealProgress.wordsToShow(10, playedBytes = 20, receivedBytes = 100, previous = 5))
    }

    @Test fun cappedAtTotalWords() {
        assertEquals(5, RevealProgress.wordsToShow(totalWords = 5, playedBytes = 100, receivedBytes = 100, previous = 8))
    }
}
