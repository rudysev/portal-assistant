package com.portal.assistant.conversation

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.portal.assistant.audio.PrebuiltTrack
import com.portal.assistant.audio.SpeechAudio
import java.util.concurrent.Executors

/**
 * The thin player shell for the [ListeningTone]: plays [ListeningTone.synthesize] on a static-mode [AudioTrack]
 * with the **same audio attributes as Jarvis's voice** ([SpeechAudio]), so the volume keys move both together and
 * a muted/zero volume silences the tone too.
 *
 * **Built once, replayed.** The track is built (and the samples written into it) at [prewarm], off the wake path,
 * and every play — the conversation's cue and the Settings preview alike — rewinds and replays that one track
 * ([PrebuiltTrack], which also rebuilds it if it has become unusable). A play that arrives while the tone is still
 * sounding restarts it from the top.
 *
 * Off the hot path: [play] only hands the work to a dedicated thread and returns, so the caller (the engine's
 * conversation start, on the main thread) never waits on it. That one thread serializes every play. A failure to
 * play is logged and swallowed — the tone is a cue, never a reason to block or fail a conversation.
 */
object ListeningTonePlayer {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "listening-tone").apply { isDaemon = true }
    }

    /** Synthesized once, on the worker — at [prewarm], else the first time the tone plays. */
    private val samples: ShortArray by lazy { ListeningTone.synthesize() }

    /** The tone's track, kept for the process's life; only touched on [worker]. */
    private val prebuiltTrack = PrebuiltTrack("listening tone") { StaticToneTrack(samples) }

    /**
     * Synthesize the tone and build its track ahead of the first conversation, off the wake path (cold synthesis
     * measured ~140 ms on gen2; a per-play build put the tone +155…+175 ms after mic open). Async.
     */
    fun prewarm() = worker.execute { prebuiltTrack.prepare() }

    /**
     * Play the tone once, asynchronously. Exactly one callback runs, on the worker thread: [onStarted] with the
     * [SystemClock.elapsedRealtime] at which playback started, or [onFailed] (already logged) if it couldn't play.
     */
    fun play(onStarted: (startedAtMs: Long) -> Unit, onFailed: () -> Unit) {
        worker.execute {
            if (prebuiltTrack.play()) onStarted(SystemClock.elapsedRealtime()) else onFailed()
        }
    }

    /** A static-mode [AudioTrack] holding [pcm], replayable from its start. */
    private class StaticToneTrack(pcm: ShortArray) : PrebuiltTrack.Track {
        private val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(SpeechAudio.attributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(ListeningTone.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        init {
            runCatching {
                check(audioTrack.write(pcm, 0, pcm.size) == pcm.size) { "short write" }
            }.onFailure {
                audioTrack.release()
                throw it
            }
        }

        /**
         * Rewind and play from the start. Detects what the platform reports — an uninitialized track, a refused
         * rewind, `stop()`/`play()` throwing — but not a native start that fails silently (`play()` returns no
         * status; the client restores its server-side track after an audio-server restart on its own).
         *
         * Between plays the finished track stays "started" to the platform (nothing stops it without a timer); the
         * audio server drops a static track from its active mix once its buffer has played, so nothing is held.
         */
        override fun replay() {
            check(audioTrack.state == AudioTrack.STATE_INITIALIZED) { "not initialized (state ${audioTrack.state})" }
            audioTrack.stop() // a static track that played to its end still reports PLAYING; it must be stopped to rewind
            val rewound = audioTrack.reloadStaticData()
            check(rewound == AudioTrack.SUCCESS) { "rewind failed ($rewound)" }
            audioTrack.play()
        }

        override fun release() = audioTrack.release()
    }
}
