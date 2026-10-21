package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure JVM tests for the Listening tone, at the level a listener would notice: it is short, soft (well below
 * full scale), and starts and ends at silence (no click).
 */
class ListeningToneTest {

    private val samples = ListeningTone.synthesize()

    /** Fraction of full scale for a 16-bit sample. */
    private fun level(sample: Short): Double = abs(sample.toDouble()) / Short.MAX_VALUE

    @Test fun theSuppressionWindowCoversTheWholeTonePlusAnEchoTail() {
        assertTrue(ListeningTone.suppressionWindowMs > ListeningTone.DURATION_MS)
    }

    @Test fun lastsAboutAQuarterOfASecond() {
        val durationMs = samples.size * 1000.0 / ListeningTone.SAMPLE_RATE
        assertTrue("duration $durationMs ms", durationMs in 200.0..300.0)
    }

    @Test fun peaksAtTheTargetLevelWellBelowFullScale() {
        val peak = samples.maxOf { level(it) }
        assertEquals(ListeningTone.TARGET_PEAK, peak, 0.01)
        assertTrue("peak $peak", peak < 0.5)
    }

    @Test fun startsAndEndsAtSilence() {
        assertTrue("first sample ${samples.first()}", level(samples.first()) < 0.01)
        assertTrue("last sample ${samples.last()}", level(samples.last()) < 0.01)
    }
}
