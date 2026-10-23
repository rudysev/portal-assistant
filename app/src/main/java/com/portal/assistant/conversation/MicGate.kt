package com.portal.assistant.conversation

import com.portal.assistant.audio.PcmGain
import com.portal.commons.PcmCaptureFormat
import com.portal.commons.PcmLevel

/**
 * The **Mic gate**: every mic frame of a conversation goes through it. For each frame it decides which audio is sent
 * to the voice backend and in what order, what the listening indicators show, whether the user was heard speaking,
 * and what to write to the debug log. The engine's capture callback only applies the [FrameOutcome].
 *
 * The rules it runs, in order:
 * - **Listening tone suppression:** when this conversation plays the [ListeningTone] ([playsListeningTone]), a frame
 *   captured while the tone is pending (mic open, tone not started yet) or before its suppression window ends (tone
 *   start + [ListeningTone.suppressionWindowMs]) is dropped outright: not connect-buffered, not sent, no level and no
 *   speech heard. Nothing from before the cue is sent, and the voice backend never hears the tone; the tone would
 *   otherwise read as the user speaking. While listening such a frame still shows the orange bar, at rest, so the bar
 *   appears on time, as without the tone. A tone that failed to play ends the wait: frames are sent as if it were off.
 * - **Half-duplex mute:** a frame is sent only while listening; while Jarvis speaks it is dropped.
 * - **Connect buffer:** for a voice query ([voiceQuery] — wake word or mic tap), frames captured while the socket
 *   connects are kept, so the opening words spoken during "Connecting…" aren't clipped, and flushed in capture order
 *   ahead of the first listening frame. A chip's query is the text turn, so it buffers nothing (buffering would only
 *   flush connect-window room noise alongside it). Capped at [CONNECT_BUFFER_MAX_FRAMES], dropping the oldest.
 *   Buffering ends at the first listening frame, or when listening stops first (a model turn won the race), so the
 *   model's own turn is never buffered and sent back. A one-breath *wake* "hey jarvis what's the weather" stays
 *   gated by portal-wake's detection upstream, so the buffer only helps conversations that did start. The tone's
 *   suppression window outlasts the ~440 ms connect, so with the tone on the buffer usually holds little or nothing.
 * - **Mic gain:** [MIC_GAIN] on everything sent — the single place outbound mic audio is processed.
 * - **Speech heard:** the gained frame (what the server hears, so distant late speech still registers) is measured
 *   against the no-speech grace's [NoSpeechGrace.VAD_LEVEL]. The indicators get the raw level instead.
 *
 * Deterministic and JVM-tested: no Android, no I/O, and no clock reads — each frame's capture time is passed in. It
 * holds this conversation's frame-path state (see Threads).
 *
 * **Threads.** Built once per conversation at mic open, before the capture thread starts, so its initial state is
 * visible to that thread. Only the capture thread calls [onFrame] and touches the connect buffer and the tone drop
 * counts, so they need no lock. [listeningStarted] and [listeningStopped] come from the orchestration thread,
 * [toneStarted] and [toneFailed] from the tone player's; what they write is the only volatile state.
 */
class MicGate(voiceQuery: Boolean, playsListeningTone: Boolean) {

    /** What the listening indicators (the in-app visualizer and the orange bar) show for a frame. */
    sealed interface Indicator {
        /** Leave the indicators alone: connecting, or muted while Jarvis speaks. */
        data object None : Indicator

        /** Listening, but this frame was dropped for the Listening tone: show the orange bar, with no level. */
        data object BarAtRest : Indicator

        /** Listening, at this raw mic [level] (0..1). */
        data class Level(val level: Float) : Indicator
    }

    /** What to do with one captured frame. */
    data class FrameOutcome(
        /** The audio to send to the voice backend, in order; empty when the frame is buffered or dropped. */
        val audioToSend: List<ByteArray>,
        val indicator: Indicator,
        /** Whether the gained frame cleared [NoSpeechGrace.VAD_LEVEL] — the no-speech grace's local-speech signal. */
        val speechHeard: Boolean,
        val logLines: List<String>,
    )

    /** Where this conversation's Listening tone is, as the mic sees it — decides which frames [suppresses] drops. */
    private sealed interface TonePlayback {
        /** No tone this conversation (setting off, a chip, or it failed to play): nothing is dropped. */
        data object None : TonePlayback

        /** The mic is open but the tone hasn't started yet: every frame is dropped — none was captured after the cue. */
        data object Pending : TonePlayback

        /** The tone started at [startedAtMs]: frames captured before its suppression window ends are dropped. */
        data class Started(val startedAtMs: Long) : TonePlayback
    }

    @Volatile private var listening = false

    /** True only for a voice query's initial connect window, until the first listening frame or listening stops. */
    @Volatile private var connectBuffering = voiceQuery

    @Volatile private var tonePlayback: TonePlayback = if (playsListeningTone) TonePlayback.Pending else TonePlayback.None

    /** Capture-thread only; bounded and per-conversation, so it is freed with the gate. */
    private val connectBuffer = ArrayDeque<ByteArray>()

    /** Capture-thread only: frames dropped for the Listening tone, and the capture offsets they spanned (for the log). */
    private var tonePendingDroppedFrames = 0
    private var toneWindowDroppedFrames = 0
    private var toneWindowDroppedFromMs = 0L
    private var toneWindowDroppedUntilMs = 0L
    private var toneDropReported = false

    /** Orchestration thread: Jarvis is listening (send frames from now). */
    fun listeningStarted() {
        listening = true
    }

    /**
     * Orchestration thread: Jarvis stopped listening (it is speaking, or the conversation is tearing down). Also ends
     * connect buffering, so a model turn that begins before the first listening frame can't leave the gate buffering
     * its speech. Normally a no-op for buffering: the first listening frame already ended it.
     */
    fun listeningStopped() {
        listening = false
        connectBuffering = false
    }

    /** Tone player's thread: the Listening tone started at [atMs], on the same monotonic clock as the frames. */
    fun toneStarted(atMs: Long) {
        tonePlayback = TonePlayback.Started(atMs)
    }

    /** Tone player's thread: the Listening tone didn't play, so frames are sent as if it were off. */
    fun toneFailed() {
        tonePlayback = TonePlayback.None
    }

    /**
     * Capture thread: decide what happens to the first [length] bytes of [pcm], captured until [capturedUntilMs] (a
     * monotonic clock).
     */
    fun onFrame(pcm: ByteArray, length: Int, capturedUntilMs: Long): FrameOutcome {
        val logLines = ArrayList<String>(1)
        if (droppedForListeningTone(capturedUntilMs, logLines)) {
            val indicator = if (listening) Indicator.BarAtRest else Indicator.None
            return FrameOutcome(emptyList(), indicator, speechHeard = false, logLines)
        }
        if (listening) {
            val gained = withMicGain(pcm, length)
            val audioToSend = ArrayList<ByteArray>(connectBuffer.size + 1)
            if (connectBuffering) { // first listening frame: flush what was captured while connecting, in order
                connectBuffering = false
                if (connectBuffer.isNotEmpty()) logLines += flushLine(connectBuffer.size)
                while (connectBuffer.isNotEmpty()) audioToSend += connectBuffer.removeFirst()
            }
            audioToSend += gained
            return FrameOutcome(
                audioToSend,
                Indicator.Level(PcmLevel.normalized(pcm, length)),
                speechHeard = PcmLevel.normalized(gained) >= NoSpeechGrace.VAD_LEVEL,
                logLines,
            )
        }
        if (connectBuffering) { // connecting: the socket isn't ready, so keep the user's first words
            if (connectBuffer.size >= CONNECT_BUFFER_MAX_FRAMES) connectBuffer.removeFirst()
            connectBuffer.addLast(withMicGain(pcm, length))
        }
        // else: muted while Jarvis speaks (buffering here would capture and echo the model's own turn)
        return FrameOutcome(emptyList(), Indicator.None, speechHeard = false, logLines)
    }

    /**
     * Whether this frame is dropped for the Listening tone ([suppresses]); adds the one-time drop report to [logLines]
     * at the first frame kept after the window (or after a tone that failed to play).
     *
     * A frame's capture span is taken as the [PcmCaptureFormat.FRAME_MS] ending at [capturedUntilMs], its delivery.
     * Delivery lags the real capture by the input latency (plus ≤ one 20 ms poll), a steady offset;
     * [ListeningTone.ECHO_TAIL_MS] was measured on this same clock and stamping, so the window lines up with where the
     * tone really lands in frames.
     */
    private fun droppedForListeningTone(capturedUntilMs: Long, logLines: MutableList<String>): Boolean {
        val tonePlaybackNow = tonePlayback
        val capturedFromMs = capturedUntilMs - PcmCaptureFormat.FRAME_MS
        val dropped = suppresses(tonePlaybackNow, capturedFromMs)
        when (tonePlaybackNow) { // bookkeeping for the report only; the rule is suppresses'
            TonePlayback.None ->
                if (tonePendingDroppedFrames > 0) reportToneDropOnce("then the tone didn't play, so frames are sent again", logLines)

            TonePlayback.Pending -> tonePendingDroppedFrames++

            is TonePlayback.Started -> {
                val startedAtMs = tonePlaybackNow.startedAtMs
                if (dropped) {
                    if (toneWindowDroppedFrames++ == 0) toneWindowDroppedFromMs = capturedFromMs - startedAtMs
                    toneWindowDroppedUntilMs = capturedUntilMs - startedAtMs
                } else {
                    val droppedInWindow = if (toneWindowDroppedFrames == 0) "nothing" else
                        "$toneWindowDroppedFrames frame(s) (${toneWindowDroppedFrames * PcmCaptureFormat.FRAME_MS}ms) " +
                            "captured ${toneWindowDroppedFromMs}..${toneWindowDroppedUntilMs}ms from the tone's start"
                    reportToneDropOnce("then $droppedInWindow in its window (${ListeningTone.suppressionWindowMs}ms)", logLines)
                }
            }
        }
        return dropped
    }

    private fun reportToneDropOnce(afterPending: String, logLines: MutableList<String>) {
        if (toneDropReported) return
        toneDropReported = true
        logLines += "listening tone: dropped $tonePendingDroppedFrames frame(s) while pending, $afterPending"
    }

    /**
     * Whether a frame whose capture began at [frameCapturedFromMs] must be dropped: always while the tone is
     * [TonePlayback.Pending]; once [TonePlayback.Started], unless the frame began after the suppression window ended —
     * nothing captured before the cue may be sent, nor the tone and its echo (a frame straddling the window's end
     * goes whole); never for [TonePlayback.None].
     */
    private fun suppresses(tonePlayback: TonePlayback, frameCapturedFromMs: Long): Boolean =
        when (tonePlayback) {
            TonePlayback.None -> false
            TonePlayback.Pending -> true
            is TonePlayback.Started -> frameCapturedFromMs < tonePlayback.startedAtMs + ListeningTone.suppressionWindowMs
        }

    /** The single place outbound mic audio is processed: [MIC_GAIN]. */
    private fun withMicGain(pcm: ByteArray, length: Int): ByteArray = PcmGain.amplify(pcm, length, MIC_GAIN)

    private fun flushLine(frames: Int): String =
        "connect buffer: flushing $frames frame(s) (~${frames * PcmCaptureFormat.FRAME_MS}ms) captured while connecting"

    private companion object {
        // Software gain on the sent conversation audio so room-distance speech reaches a level the backend
        // transcribes (handset mic only — no far-field array). Tunable on device; ~2800 RMS transcribed at arm's
        // length, ~1400 at 4 m did not, so ~2x lifts 4 m to the working level. 1f disables it (pass-through copy).
        const val MIC_GAIN = 2.0f

        // Cap on mic frames (100 ms each, ~3.2 KB) buffered while connecting. Bounds memory if connect is slow or
        // hung; drop-oldest keeps the most recent. 50 ≈ 5 s, well over the ~440 ms connect, so a normal first query
        // is buffered whole. ~160 KB worst case, freed with the gate.
        const val CONNECT_BUFFER_MAX_FRAMES = 50
    }
}
