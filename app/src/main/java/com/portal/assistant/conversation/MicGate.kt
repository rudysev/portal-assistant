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
 * - **Half-duplex mute:** a frame is sent only while listening; while Jarvis speaks it is dropped.
 * - **Connect buffer:** for a voice query ([voiceQuery] — wake word or mic tap), frames captured while the socket
 *   connects are kept, so the opening words spoken during "Connecting…" aren't clipped, and flushed in capture order
 *   ahead of the first listening frame. A chip's query is the text turn, so it buffers nothing (buffering would only
 *   flush connect-window room noise alongside it). Capped at [CONNECT_BUFFER_MAX_FRAMES], dropping the oldest.
 *   Buffering ends at the first listening frame, or when listening stops first (a model turn won the race), so the
 *   model's own turn is never buffered and sent back. A one-breath *wake* "hey jarvis what's the weather" stays
 *   gated by portal-wake's detection upstream, so the buffer only helps conversations that did start.
 * - **Mic gain:** [MIC_GAIN] on everything sent — the single place outbound mic audio is processed.
 * - **Speech heard:** the gained frame (what the server hears, so distant late speech still registers) is measured
 *   against the no-speech grace's [NoSpeechGrace.VAD_LEVEL]. The indicators get the raw level instead.
 *
 * Pure: no Android, no I/O, and no clock reads — each frame's capture time is passed in.
 *
 * **Threads.** Built once per conversation at mic open, before the capture thread starts, so its initial state is
 * visible to that thread. Only the capture thread calls [onFrame] and touches the connect buffer, so it needs no
 * lock. [listeningStarted] and [listeningStopped] come from the orchestration thread; what they write is the only
 * volatile state.
 */
class MicGate(voiceQuery: Boolean) {

    /** What the listening indicators (the in-app visualizer and the orange bar) show for a frame. */
    sealed interface Indicator {
        /** Leave the indicators alone: connecting, or muted while Jarvis speaks. */
        data object None : Indicator

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

    @Volatile private var listening = false

    /** True only for a voice query's initial connect window, until the first listening frame or listening stops. */
    @Volatile private var connectBuffering = voiceQuery

    /** Capture-thread only; bounded and per-conversation, so it is freed with the gate. */
    private val connectBuffer = ArrayDeque<ByteArray>()

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

    /**
     * Capture thread: decide what happens to the first [length] bytes of [pcm], captured until [capturedUntilMs] (a
     * monotonic clock).
     */
    fun onFrame(pcm: ByteArray, length: Int, capturedUntilMs: Long): FrameOutcome {
        if (listening) {
            val gained = PcmGain.amplify(pcm, length, MIC_GAIN)
            val audioToSend = ArrayList<ByteArray>(connectBuffer.size + 1)
            val logLines = ArrayList<String>(1)
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
            connectBuffer.addLast(PcmGain.amplify(pcm, length, MIC_GAIN))
        }
        // else: muted while Jarvis speaks (buffering here would capture and echo the model's own turn)
        return FrameOutcome(emptyList(), Indicator.None, speechHeard = false, logLines = emptyList())
    }

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
