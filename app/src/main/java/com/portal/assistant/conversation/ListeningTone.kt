package com.portal.assistant.conversation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The **Listening tone** — the short, soft, rising two-note tone played when a voice conversation starts, at
 * the moment the mic begins capturing audio that will be sent to the voice backend (spec:
 * `docs/specs/listening-tone.md`).
 *
 * Heard many times a day, so it is deliberately gentle: two notes a perfect fourth apart (G5 → C6), voiced as a
 * softly struck piano rather than a pure sine. Each note is a few slightly detuned "strings" (gentle beating),
 * each string a stack of slightly stretched (inharmonic) partials whose level falls with partial number and is
 * further rolled off by a soft hammer. Every partial has a quick, click-free hammer attack and a two-stage decay
 * (a fast initial drop, then a slower ring), with higher partials dying sooner. The first note keeps ringing
 * under the second (a sustain-pedal feel), and a final damper fade guarantees it ends at silence. Peak is well
 * below the Timer chime's ([com.portal.assistant.conversation.tools.ChimeSound], 0.6) so it reads as a cue,
 * not an alert.
 *
 * Generated in memory as 16-bit mono PCM at [SAMPLE_RATE] — no asset file. 24 kHz matches the voice output
 * path ([com.portal.assistant.audio.PcmPlayer]); partials above [MAX_PARTIAL_FRACTION] of the sample rate are
 * skipped so nothing aliases. Pure (no Android) so it is unit-tested on the JVM; a thin player shell plays the
 * samples.
 */
object ListeningTone {

    // --- Gating: when the tone plays ---

    /**
     * Whether the mic opening now plays the tone: only when the user has it on ([toneEnabled]), the query is
     * spoken ([voiceQuery] — wake word or mic tap; a suggestion chip is sent as text, so there's nothing to cue),
     * and this is the conversation's start ([conversationStart] — never when the mic reopens for a follow-up).
     */
    fun playsAtMicOpen(toneEnabled: Boolean, voiceQuery: Boolean, conversationStart: Boolean): Boolean =
        toneEnabled && voiceQuery && conversationStart

    /**
     * Whether flipping the Settings switch plays the tone once as a preview: only when it was switched on — off is
     * silent — and no conversation is live ([conversationLive]). A live conversation holds the mic open, and a
     * preview isn't covered by the engine's suppression window, so it would be recorded and sent to the voice
     * backend; like the Speaker preview, a conversation always wins.
     */
    fun previewsOnSwitch(switchedOn: Boolean, conversationLive: Boolean): Boolean = switchedOn && !conversationLive

    // --- Suppression window: keeping the tone out of the recording ---

    /**
     * How long past the tone's own length the mic can still hear it: speaker output latency (the sound leaves the
     * speaker after `AudioTrack.play()` returns) plus the room's echo/ring. Device-measured, like `MIC_GAIN` — not a
     * guessed timeout, and not a client timer (it only decides which frames are dropped, never turn-taking).
     *
     * Measured on gen2 (2026-10-19) at **max** voice volume (18/18, the worst case; at the 4/18 in use the tone
     * barely registers in the mic), quiet room, 8 mic-tap conversations, energy at the tone's frequencies per 20 ms,
     * on the engine's frame stamping (frame end = delivery): the tone reaches the mic +147…+299 ms after the tone
     * started (output latency varies run to run), and is back under the room's ambient maximum by +426…+549 ms
     * (faint residue at the ambient p95 to ~+650 ms, which the whole-frame overlap rule also drops). 549 ms − the
     * tone's 240 ms ≈ 310 ms, plus a ~90 ms margin → 400 ms (window 640 ms).
     */
    const val ECHO_TAIL_MS = 400L

    /** How long after the tone starts mic audio is dropped: the whole tone plus its [ECHO_TAIL_MS]. */
    val suppressionWindowMs: Long get() = DURATION_MS + ECHO_TAIL_MS

    /**
     * Whether a mic frame captured over [frameCapturedFromMs, frameCapturedUntilMs) must be dropped from the
     * recording because it may hold the tone: true when that span overlaps the suppression window starting at
     * [toneStartedAtMs]. A null [toneStartedAtMs] means no tone played, so nothing is dropped.
     */
    fun suppressesFrame(toneStartedAtMs: Long?, frameCapturedFromMs: Long, frameCapturedUntilMs: Long): Boolean =
        toneStartedAtMs != null &&
            frameCapturedUntilMs > toneStartedAtMs &&
            frameCapturedFromMs < toneStartedAtMs + suppressionWindowMs

    // --- Synthesis: what the tone sounds like ---

    /** Output sample rate (Hz) of [synthesize] — the same rate as the model's spoken audio. */
    const val SAMPLE_RATE = 24_000

    /** Peak amplitude as a fraction of full scale; matches the user-approved audition level, tuned on device. */
    const val TARGET_PEAK = 0.22

    /** Total length of the tone, including its fade to silence. */
    const val DURATION_MS = 240L

    // --- Tuning: the two notes (a rising perfect fourth) ---
    private const val FIRST_NOTE_HZ = 783.99 // G5
    private const val SECOND_NOTE_HZ = 1046.50 // C6
    private const val FIRST_NOTE_WEIGHT = 0.8 // slightly under the second, so the rise lands on the top note
    private const val SECOND_NOTE_WEIGHT = 1.0
    private const val SECOND_NOTE_ONSET_MS = 70.0 // struck while the first still rings — a soft overlap

    // --- Tuning: strings (a piano note is 2–3 strings, tuned a hair apart → gentle beating, warmth) ---
    private val STRING_DETUNE_HZ = doubleArrayOf(-0.7, 0.0, 0.8)

    // --- Tuning: partials (timbre) ---
    private const val PARTIAL_COUNT = 8
    private const val INHARMONICITY = 0.0004 // string stiffness B: partial n sits at n·f0·√(1 + B·n²)
    private const val HAMMER_ROLLOFF = 0.75 // soft hammer: partial n is further scaled by e^(−ROLLOFF·(n−1))
    private const val MAX_PARTIAL_FRACTION = 0.45 // skip partials above this fraction of SAMPLE_RATE (Nyquist 0.5)

    // --- Tuning: envelope ---
    private const val ATTACK_MS = 3.0 // raised-cosine hammer attack — fast, but no click
    private const val PROMPT_DECAY_MS = 35.0 // fast first stage of the decay (time constant, amplitude → 1/e)
    private const val RING_DECAY_MS = 160.0 // slower second stage — the ring that carries under the next note
    private const val PROMPT_FRACTION = 0.65 // share of each partial's level in the fast stage; the rest rings
    private const val PARTIAL_DECAY_SLOPE = 0.35 // partial n decays (1 + SLOPE·(n−1))× faster than the fundamental
    private const val FINAL_FADE_MS = 50.0 // raised-cosine damper fade so the last sample is exactly silence

    /** The Listening tone as 16-bit mono PCM at [SAMPLE_RATE], [DURATION_MS] long, peak at [TARGET_PEAK]. */
    fun synthesize(): ShortArray {
        val totalSamples = (DURATION_MS * SAMPLE_RATE / 1000).toInt()
        val mix = DoubleArray(totalSamples)
        addNote(mix, FIRST_NOTE_HZ, FIRST_NOTE_WEIGHT, onsetMs = 0.0)
        addNote(mix, SECOND_NOTE_HZ, SECOND_NOTE_WEIGHT, SECOND_NOTE_ONSET_MS)
        applyFinalFade(mix)
        val peak = mix.maxOf { abs(it) }
        val scale = if (peak > 0.0) TARGET_PEAK * Short.MAX_VALUE / peak else 0.0
        return ShortArray(totalSamples) { (mix[it] * scale).toInt().toShort() }
    }

    /** Mix one softly struck piano note into [mix]: detuned strings × stretched partials, each enveloped. */
    private fun addNote(mix: DoubleArray, frequencyHz: Double, weight: Double, onsetMs: Double) {
        val onsetSample = (onsetMs * SAMPLE_RATE / 1000).toInt()
        val stringWeight = weight / STRING_DETUNE_HZ.size
        for (detuneHz in STRING_DETUNE_HZ) {
            val stringHz = frequencyHz + detuneHz
            for (partial in 1..PARTIAL_COUNT) {
                val partialHz = partial * stringHz * sqrt(1.0 + INHARMONICITY * partial * partial)
                if (partialHz > MAX_PARTIAL_FRACTION * SAMPLE_RATE) break
                val level = stringWeight / partial * exp(-HAMMER_ROLLOFF * (partial - 1))
                val decaySpeedup = 1.0 + PARTIAL_DECAY_SLOPE * (partial - 1)
                addPartial(mix, onsetSample, partialHz, level, decaySpeedup)
            }
        }
    }

    /** Mix one partial into [mix] from [onsetSample]: hammer attack, then the two-stage (prompt + ring) decay. */
    private fun addPartial(mix: DoubleArray, onsetSample: Int, frequencyHz: Double, level: Double, decaySpeedup: Double) {
        val attackSec = ATTACK_MS / 1000
        val promptDecaySec = PROMPT_DECAY_MS / 1000 / decaySpeedup
        val ringDecaySec = RING_DECAY_MS / 1000 / decaySpeedup
        for (index in onsetSample until mix.size) {
            val elapsedSec = (index - onsetSample).toDouble() / SAMPLE_RATE
            val attack = if (elapsedSec < attackSec) raisedCosine(elapsedSec / attackSec) else 1.0
            val decay = PROMPT_FRACTION * exp(-elapsedSec / promptDecaySec) +
                (1.0 - PROMPT_FRACTION) * exp(-elapsedSec / ringDecaySec)
            mix[index] += level * attack * decay * sin(2.0 * PI * frequencyHz * elapsedSec)
        }
    }

    /** Fade the last [FINAL_FADE_MS] of [mix] to exactly zero, so the tone never ends on a click. */
    private fun applyFinalFade(mix: DoubleArray) {
        val fadeSamples = minOf(mix.size, (FINAL_FADE_MS * SAMPLE_RATE / 1000).toInt())
        if (fadeSamples < 2) return
        val fadeStart = mix.size - fadeSamples
        for (offset in 0 until fadeSamples) {
            mix[fadeStart + offset] *= raisedCosine(1.0 - offset.toDouble() / (fadeSamples - 1))
        }
    }

    /** Smooth 0→1 ramp over [progress] in 0..1 (half a cosine period) — gentler than a linear ramp. */
    private fun raisedCosine(progress: Double): Double = 0.5 - 0.5 * cos(PI * progress)
}
