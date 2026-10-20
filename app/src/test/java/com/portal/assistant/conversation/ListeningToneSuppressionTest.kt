package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Listening tone's suppression window: which mic frames are dropped from the recording so
 * the voice backend never hears Jarvis's own tone, while speech after the tone (and its echo tail) is kept.
 * A frame is described by the span of time it was captured over; times are on one monotonic clock.
 */
class ListeningToneSuppressionTest {

    private val toneStartedAtMs = 50_000L
    private val windowEndsAtMs = toneStartedAtMs + ListeningTone.suppressionWindowMs

    @Test fun aFrameCapturedAtToneStartIsDropped() {
        assertTrue(ListeningTone.suppressesFrame(toneStartedAtMs, toneStartedAtMs, toneStartedAtMs + 100))
    }

    @Test fun aFrameThatStartedBeforeTheToneButHoldsItsOnsetIsDropped() {
        assertTrue(ListeningTone.suppressesFrame(toneStartedAtMs, toneStartedAtMs - 60, toneStartedAtMs + 40))
    }

    @Test fun aFrameCapturedJustBeforeTheWindowEndsIsDropped() {
        assertTrue(ListeningTone.suppressesFrame(toneStartedAtMs, windowEndsAtMs - 101, windowEndsAtMs - 1))
    }

    @Test fun aFrameStraddlingTheWindowEndIsDropped() {
        // Its first samples can still hold the tone's echo, so the whole frame goes.
        assertTrue(ListeningTone.suppressesFrame(toneStartedAtMs, windowEndsAtMs - 1, windowEndsAtMs + 99))
    }

    @Test fun aFrameCapturedJustAfterTheWindowEndsIsKept() {
        // The user's words right after the tone (and its echo) must reach the voice backend.
        assertFalse(ListeningTone.suppressesFrame(toneStartedAtMs, windowEndsAtMs, windowEndsAtMs + 100))
    }

    @Test fun aFrameCapturedBeforeTheToneStartedIsKept() {
        // Only audio that can hold the tone is lost; a frame that ended as the tone began has none of it.
        assertFalse(ListeningTone.suppressesFrame(toneStartedAtMs, toneStartedAtMs - 100, toneStartedAtMs))
    }

    @Test fun withNoTonePlayedNothingIsDropped() {
        // A chip, a follow-up, the setting off, or a tone that failed to play — the recording is untouched.
        assertFalse(ListeningTone.suppressesFrame(null, toneStartedAtMs, toneStartedAtMs + 100))
    }

    @Test fun theWindowCoversTheWholeTonePlusAnEchoTail() {
        assertTrue(ListeningTone.suppressionWindowMs > ListeningTone.DURATION_MS)
    }
}
