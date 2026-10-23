package com.portal.assistant.wake

/**
 * One loaded "hey jarvis" detector on the mic — the port [ForegroundWake] arms and pauses. Production:
 * [VoskDetector]; tests: a recording fake. Its wake, unavailable and capture-loss callbacks may arrive on any thread;
 * [ForegroundWake] hops them to main.
 */
interface Detector {
    /** Open the mic and listen (or resume). A mic that won't open, or later dies, arrives as a capture loss. */
    fun start()

    /** Release the mic but keep the model loaded, so the next [start] is instant. Idempotent. */
    fun pause()

    /** Release the mic and unload the model. */
    fun close()
}
