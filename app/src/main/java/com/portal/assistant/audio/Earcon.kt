package com.portal.assistant.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app's short UI sounds, **synthesized in memory** — so nothing depends on the device's (unknown) system
 * sounds and no audio assets ship in the APK.
 *
 * Pure by design: a [Spec] is plain data and [synthesize] is a deterministic function of it (no Android, no
 * I/O, no threads), so both the tone design and the sample math are unit-testable — the same pure-logic +
 * thin-shell split the conversation uses. Playing one is [EarconPlayer]'s job.
 *
 * Two earcons exist:
 *  - [TIMER_ALERT] — the timer's "your timer is done" bell, meant to carry across a room.
 *  - [WAKE_LISTENING] — the single soft note played once when the assistant starts listening after a
 *    hands-free "hey jarvis" (see `AssistantEngine`). Deliberately shorter, softer, and higher than the
 *    timer bell so the two are never confused.
 */
object Earcon {

    const val SAMPLE_RATE = 44_100

    private const val SHORT_MIN = Short.MIN_VALUE.toInt()
    private const val SHORT_MAX = Short.MAX_VALUE.toInt()

    /** One voice within a strike: a fundamental at [hz], mixed in at relative [weight]. */
    data class Note(val hz: Double, val weight: Double)

    /** A struck cluster of [notes] beginning at [atSec], ringing for [lenSec] before it's cut. */
    data class Strike(val atSec: Double, val lenSec: Double, val notes: List<Note>)

    /**
     * A complete earcon. [decayRate] is the exponential ring-off of the fundamental (higher = shorter),
     * [attackSec] the fade-in that kills the click, [partials] the harmonic amplitudes at 1×/2×/3×… each
     * note's fundamental (the timbre), and [targetPeak] the fraction of full scale the finished sound is
     * normalized to. [name] is for logging only.
     *
     * Two optional shaping terms give a struck-string (piano) character; both default to off, which reduces
     * the math exactly to a plain harmonic bell:
     *  - [partialDecay] — how much faster each partial above the fundamental dies (partial *h* decays at
     *    `decayRate × (1 + partialDecay × h)`). This is the defining piano trait: the bright hammer strike
     *    collapses to a near-sine within a few tens of ms.
     *  - [inharmonicity] — string stiffness `B`: partial *h* sits at `h·f·√(1 + B·h²)` rather than exactly
     *    `h·f`. A tiny value keeps a synthesized note from sounding like an organ.
     */
    data class Spec(
        val name: String,
        val strikes: List<Strike>,
        val totalSec: Double,
        val targetPeak: Double,
        val decayRate: Double,
        val attackSec: Double,
        val partials: List<Double>,
        val partialDecay: Double = 0.0,
        val inharmonicity: Double = 0.0,
    ) {
        /** How long the sound runs, in ms — what a caller waits out before releasing the track. */
        val durationMs: Long get() = (totalSec * 1000).toLong()
    }

    // C5–E5–G5–C6 major chord; weighted so the top notes shimmer under the root.
    private val BELL_CHORD = listOf(
        Note(hz = 523.25, weight = 1.0),
        Note(hz = 659.25, weight = 0.8),
        Note(hz = 783.99, weight = 0.6),
        Note(hz = 1046.50, weight = 0.45),
    )

    /**
     * The timer alert: a major-chord bell struck twice with a natural decay. Values are device-tuned — the
     * 0.60 peak was raised from 0.30/0.45, which were too soft to notice from across the room.
     */
    val TIMER_ALERT = Spec(
        name = "timer",
        strikes = listOf(0.0, 1.1).map { at -> Strike(atSec = at, lenSec = 1.0, notes = BELL_CHORD) },
        totalSec = 2.3,
        targetPeak = 0.60,
        decayRate = 4.0,
        attackSec = 0.008,
        partials = listOf(1.0, 0.4, 0.15), // fundamental + 2 partials
    )

    /**
     * The wake earcon: a **single soft Rhodes-like C6**, struck once and cut before it can ring on —
     * auditioned on device against piano, marimba, glockenspiel and two-note variants. An electric piano is
     * essentially a sine with a bell-like tine over it, so the partial set is sparse (the 4th is the tine)
     * and [partialDecay] collapses everything above the fundamental within a few tens of ms, leaving a round
     * tone. Low [targetPeak] and a gentle 8 ms attack keep it an unobtrusive "I'm listening", not an alarm.
     *
     * Its [durationMs] is also how long the engine stops forwarding mic audio, so keep it short.
     */
    val WAKE_LISTENING = Spec(
        name = "wake",
        strikes = listOf(Strike(atSec = 0.0, lenSec = 0.38, notes = listOf(Note(hz = 1046.50, weight = 1.0)))),
        totalSec = 0.44,
        targetPeak = 0.26,
        decayRate = 11.0,
        attackSec = 0.008,
        partials = listOf(1.0, 0.18, 0.05, 0.10), // fundamental + a whisper of tine on the 4th
        partialDecay = 1.6,
        inharmonicity = 0.0004,
    )

    /**
     * Render [spec] as 16-bit mono PCM at [SAMPLE_RATE]: summed partials per note, an exponential decay per
     * strike, then peak-normalized to the spec's target so every earcon lands at a predictable loudness
     * regardless of how many voices it mixes.
     */
    fun synthesize(spec: Spec): ShortArray {
        val n = (spec.totalSec * SAMPLE_RATE).toInt()
        val buf = DoubleArray(n)
        for (strike in spec.strikes) {
            val start = (strike.atSec * SAMPLE_RATE).toInt()
            val strikeSamples = (strike.lenSec * SAMPLE_RATE).toInt()
            for (note in strike.notes) {
                for (i in 0 until strikeSamples) {
                    val idx = start + i
                    if (idx >= n) break
                    val t = i.toDouble() / SAMPLE_RATE
                    val attack = if (spec.attackSec > 0.0) min(1.0, t / spec.attackSec) else 1.0
                    val w = 2.0 * PI * note.hz * t
                    var tone = 0.0
                    spec.partials.forEachIndexed { h, amp ->
                        val harmonic = h + 1
                        // Both shaping terms collapse to identity when off (stretch = √1, env = the plain
                        // decay), so a bell spec needs no separate branch — verified sample-for-sample against
                        // the pre-refactor renderer in EarconTimerParityTest.
                        val stretch = sqrt(1.0 + spec.inharmonicity * harmonic * harmonic)
                        val env = exp(-t * spec.decayRate * (1.0 + spec.partialDecay * h))
                        tone += amp * env * sin(harmonic * stretch * w)
                    }
                    buf[idx] += note.weight * attack * tone
                }
            }
        }
        var peak = 0.0
        for (v in buf) peak = maxOf(peak, abs(v))
        val scale = if (peak > 0) spec.targetPeak * Short.MAX_VALUE / peak else 0.0
        // Clamp, don't wrap: a Spec is meant to be a safe one-block retune knob, and a targetPeak above 1.0
        // would otherwise fold every waveform peak to the opposite rail — a phase-inverted buzz instead of
        // the loud tone the author asked for. A no-op for every in-range spec.
        return ShortArray(n) { (buf[it] * scale).toInt().coerceIn(SHORT_MIN, SHORT_MAX).toShort() }
    }
}
