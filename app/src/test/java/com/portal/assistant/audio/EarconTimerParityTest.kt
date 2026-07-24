package com.portal.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Locks the **timer alert** to the sound it has always been.
 *
 * The timer bell predates [Earcon]: it was `ChimeSound.synthesize`, a hard-coded chord loop, and users have
 * been hearing it for releases. Generalizing it into a data-driven [Earcon.Spec] must not have retuned it,
 * and neither must any future change to the synth — so [legacyChimeSound] below is that original algorithm,
 * verbatim, kept as an independent oracle. If a synth change alters the rendering, this fails.
 *
 * The wake earcon deliberately has no such lock: it is new, and its `Spec` is meant to be retuned freely.
 */
class EarconTimerParityTest {

    // Slack for last-ULP FP re-association across platforms (see the assertion). Well below any audible
    // retune, which moves samples by orders of magnitude more.
    private val FP_SLACK = 4

    @Test
    fun `TIMER_ALERT renders as the original ChimeSound did`() {
        val expected = legacyChimeSound()
        val actual = Earcon.synthesize(Earcon.TIMER_ALERT)
        assertEquals("sample count", expected.size, actual.size)
        val maxDiff = expected.indices.maxOf { abs(expected[it] - actual[it]) }
        // The two are mathematically identical but re-associated: the oracle applies one decay envelope to
        // the summed partials, the shipped code distributes it into each partial. That last-ULP difference
        // can push a boundary sample across the toInt() truncation step, so an exact-equality assertion would
        // be fragile on a JVM whose Math.sin/exp round differently. A handful of units is rounding noise; any
        // real retune (a changed note, weight, or decay) shifts hundreds of samples by hundreds of units.
        assertTrue("timer bell retuned (maxDiff=$maxDiff of ±32767)", maxDiff <= FP_SLACK)
    }

    /**
     * `ChimeSound.synthesize` as it was before the [Earcon] refactor — a C5–E5–G5–C6 chord struck twice with
     * one envelope over the whole harmonic stack. Deliberately duplicated rather than expressed via [Earcon],
     * so it stays an independent check and not a restatement of the code under test.
     */
    private fun legacyChimeSound(): ShortArray {
        val sampleRate = 44_100
        val targetPeak = 0.60
        val notesHz = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
        val noteWeights = doubleArrayOf(1.0, 0.8, 0.6, 0.45)
        val n = (2.3 * sampleRate).toInt()
        val buf = DoubleArray(n)
        val strikeSamples = (1.0 * sampleRate).toInt()
        for (offset in doubleArrayOf(0.0, 1.1)) {
            val start = (offset * sampleRate).toInt()
            for (noteIdx in notesHz.indices) {
                val freq = notesHz[noteIdx]
                val weight = noteWeights[noteIdx]
                for (i in 0 until strikeSamples) {
                    val idx = start + i
                    if (idx >= n) break
                    val t = i.toDouble() / sampleRate
                    val env = exp(-t * 4.0) // bell-like decay
                    val attack = min(1.0, t / 0.008) // 8ms fade-in kills the click
                    val w = 2.0 * PI * freq * t
                    val tone = sin(w) + 0.4 * sin(2.0 * w) + 0.15 * sin(3.0 * w)
                    buf[idx] += weight * env * attack * tone
                }
            }
        }
        var peak = 0.0
        for (v in buf) peak = maxOf(peak, abs(v))
        val scale = if (peak > 0) targetPeak * Short.MAX_VALUE / peak else 0.0
        return ShortArray(n) { (buf[it] * scale).toInt().toShort() }
    }
}
