package com.portal.assistant.conversation.tools

import com.portal.assistant.audio.Hush

/**
 * A [Hush] whose held state the test sets; [keepPaused] counts its calls and marks it kept paused unless
 * [keepPausedGranted] is false (the escalation denied).
 */
internal class FakeHush(held: Boolean, private val keepPausedGranted: Boolean = true) : Hush {
    override var isHeld = held
    override var isKeptPaused = false
    var keepPausedCalls = 0

    override fun start() = Unit

    override fun lift() = Unit

    override fun keepPaused() {
        keepPausedCalls++
        isKeptPaused = keepPausedGranted
    }
}
