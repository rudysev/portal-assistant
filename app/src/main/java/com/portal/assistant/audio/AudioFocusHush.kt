package com.portal.assistant.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.portal.commons.DebugLog

/**
 * [Hush] as Android audio focus (ADR 0001): [start] requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` with the
 * assistant's speech attributes, so apps that follow the focus rules pause and resume themselves when [lift]
 * abandons it. Any focus loss (a music command starting playback, an incoming call) marks it no longer held, and it
 * never re-requests: Jarvis doesn't fight for the audio.
 *
 * [keepPaused] escalates the held focus to a permanent `AUDIOFOCUS_GAIN`, so every app the hush paused gets
 * `AUDIOFOCUS_LOSS` and drops its resume (measured on gen2: Spotify and Apple Music stay paused after the lift).
 *
 * [start] and [lift] run on the engine's thread, [keepPaused] and the reads on the tool thread, the focus callback on
 * the main looper: the three calls are serialised, so a late [keepPaused] can't re-take focus after the [lift], and
 * the flags are volatile for the reads.
 */
class AudioFocusHush(context: Context) : Hush {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val onFocusChange = AudioManager.OnAudioFocusChangeListener { change ->
        DebugLog.log("hush: focus change $change")
        if (change < 0) held = false
    }

    private var request: AudioFocusRequest? = null

    @Volatile private var held = false

    @Volatile private var keptPaused = false

    override val isHeld: Boolean get() = held

    override val isKeptPaused: Boolean get() = keptPaused

    @Synchronized override fun start() {
        val focus = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        request = focus
        held = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DebugLog.log("hush: start (held=$held)")
    }

    @Synchronized override fun lift() {
        val focus = request ?: return
        request = null
        held = false
        audioManager.abandonAudioFocusRequest(focus)
        DebugLog.log("hush: lift")
    }

    @Synchronized override fun keepPaused() {
        if (!held) return
        val focus = focusRequest(AudioManager.AUDIOFOCUS_GAIN)
        request = focus
        keptPaused = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DebugLog.log("hush: keep paused (granted=$keptPaused)")
    }

    // The same listener on every request: Android keys focus by it, so the escalation replaces the hush's request.
    private fun focusRequest(gain: Int): AudioFocusRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(SpeechAudio.attributes())
        .setOnAudioFocusChangeListener(onFocusChange)
        .build()
}
