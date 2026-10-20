package com.portal.assistant.conversation

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.portal.assistant.audio.SpeechAudio
import com.portal.commons.DebugLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The thin player shell for the [ListeningTone]: plays [ListeningTone.synthesize] once on a static-mode
 * [AudioTrack] with the **same audio attributes as Jarvis's voice** ([SpeechAudio]), so the volume keys move
 * both together and a muted/zero volume silences the tone too.
 *
 * Off the hot path: [play] only hands the work to a dedicated thread and returns, so the caller (the engine's
 * conversation start, on the main thread) never waits on the track build. A failure to play is logged and
 * swallowed — the tone is a cue, never a reason to block or fail a conversation.
 */
object ListeningTonePlayer {

    /** Release the track this long after the tone's last sample — covers the output pipeline's latency so the
     *  tail isn't cut. Resource cleanup only: not a conversation timer, and nothing waits on it. */
    private const val RELEASE_MARGIN_MS = 300L

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "listening-tone").apply { isDaemon = true }
    }

    /** Synthesized once, on the worker — at [prewarm], else the first time the tone plays. */
    private val samples: ShortArray by lazy { ListeningTone.synthesize() }

    /** Synthesize the tone ahead of the first conversation (cold synthesis measured ~140 ms on gen2). Async. */
    fun prewarm() = worker.execute { samples }

    /**
     * Play the tone once, asynchronously. [onStarted] runs on the worker thread with the
     * [SystemClock.elapsedRealtime] at which playback started — only if it did.
     */
    fun play(onStarted: (startedAtMs: Long) -> Unit = {}) {
        worker.execute {
            val track = runCatching { buildTrack(samples) }.getOrElse {
                DebugLog.log("listening tone: failed to build track: ${it.message}")
                return@execute
            }
            val started = runCatching {
                check(track.write(samples, 0, samples.size) == samples.size) { "short write" }
                track.play()
            }
            if (started.isFailure) {
                DebugLog.log("listening tone: failed to play: ${started.exceptionOrNull()?.message}")
                runCatching { track.release() }
                return@execute
            }
            onStarted(SystemClock.elapsedRealtime())
            worker.schedule(
                { runCatching { track.release() } },
                samples.size * 1000L / ListeningTone.SAMPLE_RATE + RELEASE_MARGIN_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack = AudioTrack.Builder()
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
}
