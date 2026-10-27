package com.portal.assistant.conversation.tools

import com.portal.assistant.audio.Hush

/**
 * A [Hush] that behaves like [com.portal.assistant.audio.AudioFocusHush] with the device's answers set by the test:
 * whether [start] is granted ([startGranted]), whether other audio was playing as it ran ([otherAudioPlaying]), and
 * whether [keepPaused]'s escalation is granted ([keepPausedGranted]). [loseFocus] plays another app taking the focus.
 * Only reachable states: nothing silences playback or keeps a pause before a granted [start].
 */
internal class FakeHush(
    private val startGranted: Boolean = true,
    private val otherAudioPlaying: Boolean = true,
    private val keepPausedGranted: Boolean = true,
) : Hush {
    private var held = false
    private var keptPaused = false
    private var silenced = false

    var keepPausedCalls = 0
        private set

    override val isSilencingPlayback: Boolean get() = held && !keptPaused && silenced

    override fun start() {
        silenced = otherAudioPlaying
        held = startGranted
    }

    override fun lift() {
        held = false
    }

    override fun keepPaused(): Boolean {
        keepPausedCalls++
        if (!held) return false
        keptPaused = keepPausedGranted
        return keptPaused
    }

    /** Another app took the focus (a call, a music app starting): no longer held, never regained. */
    fun loseFocus() {
        held = false
    }
}
