package com.portal.assistant.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.portal.commons.DebugLog
import com.portal.commons.PcmLevel
import java.util.concurrent.LinkedBlockingQueue

/**
 * Plays the Gemini Live API's native audio output: 24 kHz mono 16-bit PCM, streamed.
 *
 * Audio chunks arrive from the WebSocket off the orchestration thread, so we buffer them in a queue and
 * a single writer thread feeds [AudioTrack] in MODE_STREAM (whose write() blocks until the buffer
 * drains — natural backpressure / pacing). [enqueue] adds a chunk, [flush] drops anything not yet
 * played (used when the model is interrupted), and [onDrained] fires once the queue has emptied AND the
 * AudioTrack has clocked out its buffered tail (see [awaitTailPlayed]) — the engine treats that as "this
 * turn's audio finished playing" (half of its race-free turn-end rule).
 */
class PcmPlayer {

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var running = false

    @Volatile private var track: AudioTrack? = null

    @Volatile private var onDrained: (() -> Unit)? = null

    @Volatile private var onLevel: ((Float) -> Unit)? = null

    /** Cumulative bytes handed to [AudioTrack] this session — drives playback-synced UI (see [playedBytes]). */
    @Volatile private var playedBytes = 0L
    private var writer: Thread? = null

    fun start() {
        if (running) return
        running = true
        playedBytes = 0L
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val t = AudioTrack(
            SpeechAudio.attributes(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .setEncoding(ENCODING)
                .build(),
            maxOf(minBuf, BUFFER_BYTES),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = t
        t.play()
        writer = Thread {
            DebugLog.log("pcm player started")
            while (running) {
                val chunk = runCatching { queue.take() }.getOrNull() ?: continue
                if (chunk.isEmpty()) continue
                runCatching { t.write(chunk, 0, chunk.size) } // blocks until the buffer drains (paces playback)
                playedBytes += chunk.size
                // After playedBytes so a listener reading it (the paced-reveal fraction) sees this chunk.
                onLevel?.invoke(PcmLevel.normalized(chunk))
                if (queue.isEmpty()) {
                    // write() returns when data is *buffered*, not *played* — up to BUFFER_BYTES (~0.5 s) can
                    // still be in the AudioTrack when the queue empties. Firing onDrained now ends the turn
                    // early and the final word ("…50%") gets clipped on underrun. Wait for the hardware to
                    // actually clock the tail out first; bail the instant more audio arrives (no mid-word gap).
                    awaitTailPlayed(t)
                    if (queue.isEmpty()) {
                        onLevel?.invoke(0f)
                        onDrained?.invoke()
                    }
                }
            }
        }.also { it.start() }
    }

    /**
     * Block until [AudioTrack] has physically played everything written (its head position stops advancing),
     * so the buffered tail isn't reported drained — and the turn ended — while the last word is still audible.
     * Returns early the moment more audio is queued, so a mid-stream underrun never injects a gap.
     */
    private fun awaitTailPlayed(t: AudioTrack) {
        var last = -1
        while (running && queue.isEmpty()) {
            val pos = runCatching { t.playbackHeadPosition }.getOrDefault(0)
            if (pos == last) return // head position stalled → hardware buffer fully played out
            last = pos
            runCatching { Thread.sleep(TAIL_POLL_MS) }.getOrElse { return }
        }
    }

    /** Add a chunk of 24 kHz PCM to the playback queue. */
    fun enqueue(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    /**
     * Cumulative bytes of audio handed to [AudioTrack] so far this session. Because playback is realtime-
     * paced (blocking writes), this advances in step with the voice, so the UI can reveal the model's words
     * as they're spoken (engine deltas it per turn and compares against bytes *received*).
     */
    fun playedBytes(): Long = playedBytes

    /** Called once the queue empties (this turn's audio finished playing). */
    fun setOnDrained(cb: (() -> Unit)?) {
        onDrained = cb
    }

    /** Called with a 0..1 level per played chunk (could drive a speaking visualizer later). */
    fun setOnLevel(cb: ((Float) -> Unit)?) {
        onLevel = cb
    }

    /** Drop everything not yet played (the model was interrupted / restarts the turn). */
    fun flush() {
        queue.clear()
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    fun stop() {
        running = false
        queue.clear()
        writer?.interrupt()
        writer = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
            track?.release()
        }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000 // Live API native audio output rate
        const val CHANNEL = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_BYTES = 24_000 // ~0.5 s of headroom
        const val TAIL_POLL_MS = 20L // poll interval while waiting for AudioTrack's tail to finish playing
    }
}
