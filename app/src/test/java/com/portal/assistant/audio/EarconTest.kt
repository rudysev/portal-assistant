package com.portal.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The pure earcon synth: length, loudness, and the click-free attack. No Android — [Earcon.synthesize] is a
 * deterministic function of its [Earcon.Spec].
 */
class EarconTest {

    private fun peak(samples: ShortArray): Double = samples.maxOf { abs(it.toInt()) }.toDouble() / Short.MAX_VALUE

    @Test
    fun `sample count matches the spec duration`() {
        listOf(Earcon.TIMER_ALERT, Earcon.WAKE_LISTENING).forEach { spec ->
            assertEquals(
                "${spec.name}: sample count",
                (spec.totalSec * Earcon.SAMPLE_RATE).toInt(),
                Earcon.synthesize(spec).size,
            )
        }
    }

    @Test
    fun `output is normalized to the target peak and never clips`() {
        listOf(Earcon.TIMER_ALERT, Earcon.WAKE_LISTENING).forEach { spec ->
            val measured = peak(Earcon.synthesize(spec))
            assertEquals("${spec.name}: peak", spec.targetPeak, measured, 0.01)
            assertTrue("${spec.name}: below full scale", measured < 1.0)
        }
    }

    @Test
    fun `the attack ramp starts from silence so there is no click`() {
        listOf(Earcon.TIMER_ALERT, Earcon.WAKE_LISTENING).forEach { spec ->
            val samples = Earcon.synthesize(spec)
            assertEquals("${spec.name}: first sample", 0, samples.first().toInt())
            // Still ramping a fifth of the way into the attack — nowhere near the normalized peak.
            val early = (spec.attackSec / 5 * Earcon.SAMPLE_RATE).toInt()
            val earlyPeak = peak(samples.copyOfRange(0, early))
            assertTrue("${spec.name}: early peak $earlyPeak", earlyPeak < spec.targetPeak / 2)
        }
    }

    @Test
    fun `the wake earcon is short and soft next to the timer bell`() {
        assertTrue(Earcon.WAKE_LISTENING.durationMs < Earcon.TIMER_ALERT.durationMs / 4)
        assertTrue(Earcon.WAKE_LISTENING.targetPeak < Earcon.TIMER_ALERT.targetPeak)
        // The mic is deaf for exactly this long after a wake — keep it well under the 5 s no-speech timer.
        assertTrue(Earcon.WAKE_LISTENING.durationMs in 200..800)
    }

    @Test
    fun `an over-unity target peak clamps instead of wrapping to the opposite rail`() {
        val tooLoud = Earcon.WAKE_LISTENING.copy(name = "too-loud", targetPeak = 1.4)
        val samples = Earcon.synthesize(tooLoud)
        assertEquals("clamped to full scale", 1.0, peak(samples), 0.001)
        // A wrap would fold the loudest peak to the opposite sign; the clamped rendering must keep the same
        // sign as the in-range version of the very same waveform.
        val reference = Earcon.synthesize(Earcon.WAKE_LISTENING)
        val loudest = reference.indices.maxBy { abs(reference[it].toInt()) }
        assertTrue(
            "sign preserved at the loudest sample",
            reference[loudest].toInt() * samples[loudest].toInt() > 0,
        )
    }

    @Test
    fun `a spec with no strikes renders silence rather than dividing by a zero peak`() {
        val silent = Earcon.WAKE_LISTENING.copy(name = "silent", strikes = emptyList())
        assertTrue(Earcon.synthesize(silent).all { it.toInt() == 0 })
    }
}
