package com.portal.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.portal.commons.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * The thin I/O shell around [Earcon]: renders a spec and plays it once through a throwaway [AudioTrack]
 * (MODE_STATIC — the whole sound is written up front, so there's no writer thread to manage).
 *
 * The caller supplies the [AudioAttributes], because *where* an earcon plays is part of its meaning: the
 * timer alert rides the media stream so it follows the volume the user actually controls, while the wake
 * earcon rides the assistant stream alongside Jarvis's voice ([SpeechAudio.earconAttributes]).
 */
object EarconPlayer {

    /**
     * Samples rendered ahead of time, keyed by the [Earcon.Spec] itself (value-equal, so a retuned spec doesn't
     * reuse a stale entry). Only [prewarm] fills this — caching exists solely to keep the ~50 ms wake synthesis
     * off the latency path, so caching every played spec would just pin the 2.3 s timer bell (~198 KB) in this
     * resident process for nothing.
     */
    private val rendered = ConcurrentHashMap<Earcon.Spec, ShortArray>()

    /** Render [spec] ahead of time so the first [play] is just an AudioTrack. Called from the service's prewarm. */
    fun prewarm(spec: Earcon.Spec) {
        rendered.getOrPut(spec) { Earcon.synthesize(spec) }
    }

    /**
     * Plays [spec] on a short-lived daemon thread. [onStarted] fires the instant the sound actually begins —
     * *after* any cache-miss synthesis and the AudioTrack build, so a caller can bound the sound from its real
     * start rather than from scheduling time. [onComplete] fires once it has finished and the track is
     * released. [onComplete] runs exactly once on every path — including a synthesis/build/write failure,
     * where [onStarted] is skipped. Delivery is asynchronous, except that a failure to even spawn the thread
     * calls [onComplete] inline.
     *
     * **Fire-and-forget, and never throws.** Both callers are on threads that must not block or fail here: the
     * engine's LISTENING transition, where an escaping exception would skip the code that opens mic forwarding
     * and strand the conversation with a live-but-silent mic; and the main-thread timer alarm receiver. So all
     * of the work — a cold synthesis when [prewarm] never ran, plus AudioTrack build/write — happens off that
     * thread, and even spawning is guarded.
     */
    fun play(
        spec: Earcon.Spec,
        attributes: AudioAttributes,
        onStarted: () -> Unit = {},
        onComplete: () -> Unit = {},
    ) {
        val spawned = runCatching {
            Thread({ playBlocking(spec, attributes, onStarted, onComplete) }, "earcon-${spec.name}")
                .apply { isDaemon = true }
                .start()
        }.isSuccess
        if (!spawned) {
            DebugLog.log("earcon '${spec.name}' could not start its playback thread")
            onComplete()
        }
    }

    /**
     * The actual playback, on the earcon thread. Every step that can fail — a cache-miss synthesis, then
     * AudioTrack `build()` (throws on resource exhaustion or a mid-flight output-device change) and `write()`
     * (reports failure by negative return, not exception) — is guarded, so [onComplete] runs on every path and
     * a track that never sounded is logged rather than silently waited out. The [onComplete] contract matters:
     * the timer's [onComplete] releases the alarm receiver's wake-lock.
     */
    private fun playBlocking(
        spec: Earcon.Spec,
        attributes: AudioAttributes,
        onStarted: () -> Unit,
        onComplete: () -> Unit,
    ) {
        val samples = rendered[spec] ?: runCatching { Earcon.synthesize(spec) }.getOrElse { e ->
            DebugLog.log("earcon '${spec.name}' failed to synthesize: ${e.message}")
            onComplete()
            return
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Earcon.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrElse { e ->
            DebugLog.log("earcon '${spec.name}' failed to build: ${e.message}")
            onComplete()
            return
        }
        // write() reports failure by return value (ERROR_DEAD_OBJECT / ERROR_INVALID_OPERATION), so a short
        // write must be caught here — otherwise we'd wait out the full sound for a track that never sounded.
        val written = runCatching { track.write(samples, 0, samples.size) }.getOrDefault(AudioTrack.ERROR)
        val ok = written == samples.size && runCatching { track.play() }.isSuccess
        if (!ok) {
            runCatching { track.release() }
            DebugLog.log("earcon '${spec.name}' failed to start (wrote $written of ${samples.size})")
            onComplete()
            return
        }
        onStarted() // the tone is now audible — callers bound its length from here
        // Release after the sound has finished, then signal completion (the timer receiver releases its
        // wake-lock there).
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching { track.release() }
                onComplete()
            },
            spec.durationMs + RELEASE_GRACE_MS,
        )
    }

    /** Slack past the sound's own length before releasing the track, so the tail isn't cut on release. */
    private const val RELEASE_GRACE_MS = 300L
}
