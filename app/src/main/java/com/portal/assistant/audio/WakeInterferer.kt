package com.portal.assistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import com.portal.commons.DebugLog
import java.io.File

/**
 * Bench-only: loop a WAV/MP3 from app files on [AudioManager.STREAM_MUSIC] so speaker→mic benches can
 * measure same-device playback (kitchen music / speech) without AEC — the case openWakeWord docs warn
 * about. Armed when [INTERFERER_FILE] exists under the external files dir; no-op otherwise.
 */
class WakeInterferer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun startIfPresent() {
        stop()
        val file = File(context.getExternalFilesDir(null), INTERFERER_FILE)
        if (!file.isFile || file.length() == 0L) return
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Kitchen-realistic music level (override with files/wakeinterferer_vol = 0–100).
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val pctFile = File(context.getExternalFilesDir(null), "wakeinterferer_vol")
            val pct = pctFile.readText().trim().toIntOrNull()?.coerceIn(0, 100) ?: DEFAULT_VOLUME_PCT
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * pct / 100f).toInt().coerceAtLeast(1), 0)
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(file.absolutePath)
                isLooping = true
                setVolume(1f, 1f)
                prepare()
                start()
                player = this
            }
            DebugLog.log("wakeinterferer looping ${file.name} (${file.length()} bytes) vol=${pct}%")
        }.onFailure {
            DebugLog.log("wakeinterferer failed: ${it.message}")
            stop()
        }
    }

    fun stop() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }

    companion object {
        const val INTERFERER_FILE = "wakeinterferer.wav"
        /** Default STREAM_MUSIC level as % of max — loud enough to couple into the mic, not clip. */
        const val DEFAULT_VOLUME_PCT = 40
    }
}
