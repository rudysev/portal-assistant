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
    /** True from a granted [start] until the hush is lost to another app or [lift]ed. Never regained. */
    val isHeld: Boolean

    /** True once [keepPaused] turned the hush into a pause that outlasts it. */
    val isKeptPaused: Boolean

    /** Quiet other apps' audio. */
    fun start()

    /** Let other apps' audio come back (except a kept pause). Idempotent, and safe once the hush was lost. */
    fun lift()

    /** Keep every app the hush paused paused after the [lift]. A no-op if not held. */
    fun keepPaused()
}
