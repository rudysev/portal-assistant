package com.portal.assistant.audio

import android.media.AudioAttributes

/**
 * Shared attributes for the assistant's own audio output, so the playback path is declared in one place.
 * `USAGE_ASSISTANT` marks both the model's native-audio replies and the assistant's earcons as coming from
 * the assistant (the system ducks other media accordingly); the content type distinguishes speech from a
 * short UI sound.
 */
object SpeechAudio {

    /** Attributes for the model's spoken audio. A fresh (immutable) instance per call. */
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Attributes for the assistant's short UI sounds (the wake earcon — see
     * [Earcon.WAKE_LISTENING]): the same assistant routing as [attributes], which is proven audible on this
     * device, tagged as sonification rather than speech.
     */
    fun earconAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /**
     * Attributes for the timer alert ([Earcon.TIMER_ALERT]). Unlike the wake earcon this rides **media**, so
     * the volume the user actually controls governs it — the alarm stream is separate and always loud, which
     * is exactly the harshness the synthesized chime replaced.
     */
    fun mediaAlertAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
}
