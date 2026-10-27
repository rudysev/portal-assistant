package com.portal.assistant.audio

/**
 * One conversation's **hush**: other apps' audio (music, videos) quiet for the length of the conversation, then back
 * on its own. Best effort: a denied or lost hush changes nothing about how the conversation runs (ADR 0001).
 *
 * One per conversation, made by the engine factory and discarded with the engine; the engine starts and lifts it
 * through [com.portal.assistant.conversation.EngineAudio], and its tools see the same instance. Production adapter:
 * [AudioFocusHush].
 */
interface Hush {
    /**
     * True while the hush is what keeps other apps' audio quiet: held (granted at [start], not yet lost to another app
     * or [lift]ed), not turned into a kept pause, and other audio was playing as [start] ran (a track already paused
     * stays paused, not hushed).
     */
    val isSilencingPlayback: Boolean

    /** Quiet other apps' audio. */
    fun start()

    /** Let other apps' audio come back (except a kept pause). Idempotent, and safe once the hush was lost. */
    fun lift()

    /**
     * Keep every app the hush paused paused after the [lift]. True only if the hush is held and the pause was kept; a
     * no-op returning false if not held.
     */
    fun keepPaused(): Boolean
}
