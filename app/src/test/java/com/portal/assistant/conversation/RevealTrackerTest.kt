package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the stateful paced-reveal bookkeeping. [played] is a mutable stand-in for `PcmPlayer.playedBytes`,
 * so we can drive the played/received fraction deterministically. Text is a fixed 10-word string.
 */
class RevealTrackerTest {

    private var played = 0L
    private val tracker = RevealTracker { played }

    private val tenWords = "one two three four five six seven eight nine ten"

    @Test fun startsAtZero() {
        assertEquals(0, tracker.revealedWords)
    }

    @Test fun noOpBeforeAnyAudio() {
        tracker.reset()
        assertFalse(tracker.recompute(tenWords)) // received == 0 → nothing to reveal
        assertEquals(0, tracker.revealedWords)
    }

    @Test fun noOpWhenNoModelText() {
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 50
        assertFalse(tracker.recompute(null))
        assertEquals(0, tracker.revealedWords)
    }

    @Test fun revealsInProportionToPlayedFraction() {
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 50 // half the received audio has played
        assertTrue(tracker.recompute(tenWords))
        assertEquals(5, tracker.revealedWords)
    }

    @Test fun returnsFalseWhenCountUnchanged() {
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 50
        assertTrue(tracker.recompute(tenWords)) // → 5
        assertFalse(tracker.recompute(tenWords)) // same inputs → no advance
        assertEquals(5, tracker.revealedWords)
    }

    @Test fun isMonotonicWhenFractionDips() {
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 50
        tracker.recompute(tenWords) // → 5
        tracker.onAudioReceived(100) // received now 200, played still 50 → fraction drops to 0.25
        assertFalse(tracker.recompute(tenWords)) // never un-reveals
        assertEquals(5, tracker.revealedWords)
    }

    @Test fun capsAtTotalWords() {
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 1_000 // played runs past received → clamps to all words
        assertTrue(tracker.recompute(tenWords))
        assertEquals(10, tracker.revealedWords)
    }

    @Test fun resetRebasesPlayedAndClearsCount() {
        // First turn reveals everything; played is now high.
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 1_000
        tracker.recompute(tenWords)
        assertEquals(10, tracker.revealedWords)

        // New turn: reset re-bases played from here, so the next turn starts from 0 — not stuck at full.
        tracker.reset()
        assertEquals(0, tracker.revealedWords)
        tracker.onAudioReceived(100)
        played = 1_050 // 50 bytes since the reset → half of this turn's received
        assertTrue(tracker.recompute(tenWords))
        assertEquals(5, tracker.revealedWords)
    }

    @Test fun tracksGrowingModelTextMonotonically() {
        // The transcript text grows mid-turn (streaming deltas) while more audio arrives — the reveal must
        // track the larger word count and never regress.
        tracker.reset()
        tracker.onAudioReceived(100)
        played = 50
        assertTrue(tracker.recompute("one two three four")) // 4 words, half played → 2
        assertEquals(2, tracker.revealedWords)

        tracker.onAudioReceived(100) // received now 200
        played = 150 // 0.75 played
        assertTrue(tracker.recompute(tenWords)) // 10 words now → round(7.5) = 8, up from 2
        assertEquals(8, tracker.revealedWords)
    }
}
