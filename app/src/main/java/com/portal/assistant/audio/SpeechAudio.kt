package com.portal.assistant.audio

import android.media.AudioAttributes

/**
 * Shared attributes for the assistant's **spoken** audio output, so the playback path is declared in
 * one place. `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` mark the model's native-audio replies as
 * assistant speech (the system ducks other media accordingly).
 */
object SpeechAudio {

    /** Attributes for the model's spoken audio. A fresh (immutable) instance per call. */
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}
