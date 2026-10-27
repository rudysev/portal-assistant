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
 * Whether it silenced anything ([isSilencingPlayback]) is `AudioManager.isMusicActive` just before the request:
 * [start] runs before the conversation's own tone and player, so only another app's audio counts.
 *
 * [keepPaused] escalates the held focus to a permanent `AUDIOFOCUS_GAIN`, so every app the hush paused gets
 * `AUDIOFOCUS_LOSS` and drops its resume (measured on gen2: Spotify and Apple Music stay paused after the lift).
 *
 * [start] and [lift] run on the engine's thread, [keepPaused] and [isSilencingPlayback] on the tool thread: those
 * four are serialised on this object's lock, so a late [keepPaused] can't re-take focus after the [lift] and
 * [isSilencingPlayback] reads its flags together. The focus callback runs on the main looper outside the lock: it
 * only clears `held` (volatile), so a focus loss can land between or during those calls, e.g. just after
 * [keepPaused] found focus held.
 */
class AudioFocusHush(context: Context) : Hush {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val onFocusChange = AudioManager.OnAudioFocusChangeListener { change ->
        DebugLog.log("hush: focus change $change")
        if (change < 0) held = false
    }

    private var request: AudioFocusRequest? = null

    // Volatile: the focus callback clears it outside the lock.
    @Volatile private var held = false

    private var keptPaused = false

    private var silenced = false

    override val isSilencingPlayback: Boolean get() = synchronized(this) { held && !keptPaused && silenced }

    @Synchronized override fun start() {
        val focus = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        request = focus
        silenced = audioManager.isMusicActive
        held = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DebugLog.log("hush: start (held=$held, silencedPlayback=$silenced)")
    }

    @Synchronized override fun lift() {
        val focus = request ?: return
        request = null
        held = false
        audioManager.abandonAudioFocusRequest(focus)
        DebugLog.log("hush: lift")
    }

    @Synchronized override fun keepPaused(): Boolean {
        if (!held) return false
        val focus = focusRequest(AudioManager.AUDIOFOCUS_GAIN)
        // Kept even if denied: [lift] abandoning it releases both requests, as focus is keyed by the shared listener.
        request = focus
        keptPaused = audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        DebugLog.log("hush: keep paused (granted=$keptPaused)")
        return keptPaused
    }

    // The same listener on every request: Android keys focus by it, so the escalation replaces the hush's request.
    private fun focusRequest(gain: Int): AudioFocusRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(SpeechAudio.attributes())
        .setOnAudioFocusChangeListener(onFocusChange)
        .build()
}
