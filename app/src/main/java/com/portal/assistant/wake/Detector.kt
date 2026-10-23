package com.portal.assistant.wake

/**
 * One loaded "hey jarvis" detector on the mic — the port [ForegroundWake] arms and pauses. Production:
 * [VoskDetector]; tests: a recording fake. Its wake and unavailable callbacks may arrive on any thread;
 * [ForegroundWake] hops them to main.
 */
interface Detector {
    /** Open the mic and listen (or resume). False when capture couldn't start (a wedged capture thread). */
    fun start(): Boolean

    /** Release the mic but keep the model loaded, so the next [start] is instant. Idempotent. */
    fun pause()

    /** Release the mic and unload the model. */
    fun close()
}
