package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the no-speech grace policy: defer the listening timeout only while local speech is
 * recent and the bounded budget remains.
 */
class NoSpeechGraceTest {

    private val now = 1_000_000L

    @Test fun extendsWhenSpeechRecentAndBudgetLeft() {
        val recent = now - (NoSpeechGrace.RECENT_SPEECH_MS - 1)
        assertTrue(NoSpeechGrace.shouldExtend(now, recent, gracesUsed = 0))
        assertTrue(NoSpeechGrace.shouldExtend(now, recent, gracesUsed = NoSpeechGrace.MAX_GRACES - 1))
    }

    @Test fun noExtendWhenSpeechTooStale() {
        val stale = now - (NoSpeechGrace.RECENT_SPEECH_MS + 1)
        assertFalse(NoSpeechGrace.shouldExtend(now, stale, gracesUsed = 0))
    }

    @Test fun recencyBoundaryIsInclusive() {
        val exactlyAtWindow = now - NoSpeechGrace.RECENT_SPEECH_MS
        assertTrue(NoSpeechGrace.shouldExtend(now, exactlyAtWindow, gracesUsed = 0))
    }

    @Test fun noExtendWhenNoSpeechEverSeen() {
        // lastSpeechMs == 0L is the "never heard speech this window" sentinel — must not grace.
        assertFalse(NoSpeechGrace.shouldExtend(now, lastSpeechMs = 0L, gracesUsed = 0))
    }

    @Test fun noExtendWhenBudgetSpent() {
        val recent = now - 1
        assertFalse(NoSpeechGrace.shouldExtend(now, recent, gracesUsed = NoSpeechGrace.MAX_GRACES))
        assertFalse(NoSpeechGrace.shouldExtend(now, recent, gracesUsed = NoSpeechGrace.MAX_GRACES + 1))
    }
}
