package com.portal.assistant.conversation.tools

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.portal.commons.DebugLog
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * A soft, pleasant timer chime — a synthesized major-chord bell struck twice with a natural decay, played
 * on the **media** stream ([AudioAttributes.USAGE_MEDIA]) so it follows the volume the user actually controls
 * (the harsh default alarm tone played on the separate, always-loud alarm stream). Generated in memory and
 * played via a one-shot [AudioTrack] — no asset file, no dependence on the device's (unknown) system sounds.
 */
object ChimeSound {

    private const val SAMPLE_RATE = 44_100
    private const val TARGET_PEAK = 0.60 // fraction of full scale; tuned on device (0.30/0.45 were too soft)

    // C5–E5–G5–C6 major chord; weighted so the top notes shimmer under the root.
    private val NOTES_HZ = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
    private val NOTE_WEIGHTS = doubleArrayOf(1.0, 0.8, 0.6, 0.45)
    private val STRIKE_OFFSETS_SEC = doubleArrayOf(0.0, 1.1) // struck twice
    private const val STRIKE_LEN_SEC = 1.0
    private const val TOTAL_SEC = 2.3

    /** Plays the chime; [onComplete] runs once it has finished and the track is released (always called). */
    fun play(onComplete: () -> Unit = {}) {
        val samples = synthesize()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val ok = runCatching {
            track.write(samples, 0, samples.size)
            track.play()
        }.isSuccess
        if (!ok) {
            runCatching { track.release() }
            DebugLog.log("timer chime failed to start")
            onComplete()
            return
        }
        // Release after the sound has finished, then signal completion (the receiver releases its wake-lock).
        Handler(Looper.getMainLooper()).postDelayed(
            {
                runCatching { track.release() }
                onComplete()
            },
            (TOTAL_SEC * 1000).toLong() + 300,
        )
    }

    /** Build the chime as 16-bit mono PCM: summed bell partials with an exponential decay, peak-normalized. */
    private fun synthesize(): ShortArray {
        val n = (TOTAL_SEC * SAMPLE_RATE).toInt()
        val buf = DoubleArray(n)
        val strikeSamples = (STRIKE_LEN_SEC * SAMPLE_RATE).toInt()
        for (offset in STRIKE_OFFSETS_SEC) {
            val start = (offset * SAMPLE_RATE).toInt()
            for (noteIdx in NOTES_HZ.indices) {
                val freq = NOTES_HZ[noteIdx]
                val weight = NOTE_WEIGHTS[noteIdx]
                for (i in 0 until strikeSamples) {
                    val idx = start + i
                    if (idx >= n) break
                    val t = i.toDouble() / SAMPLE_RATE
                    val env = exp(-t * 4.0) // bell-like decay
                    val attack = min(1.0, t / 0.008) // 8ms fade-in kills the click
                    val w = 2.0 * PI * freq * t
                    val tone = sin(w) + 0.4 * sin(2.0 * w) + 0.15 * sin(3.0 * w) // fundamental + 2 partials
                    buf[idx] += weight * env * attack * tone
                }
            }
        }
        var peak = 0.0
        for (v in buf) peak = maxOf(peak, kotlin.math.abs(v))
        val scale = if (peak > 0) TARGET_PEAK * Short.MAX_VALUE / peak else 0.0
        return ShortArray(n) { (buf[it] * scale).toInt().toShort() }
    }
}
