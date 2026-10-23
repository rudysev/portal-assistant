package com.portal.assistant.wake

import com.portal.commons.audio.CaptureLoss

/**
 * One loaded "hey jarvis" detector on the mic — the port [ForegroundWake] arms and pauses. Production:
 * [VoskDetector]; tests: a recording fake. It reports to its [Listener].
 */
interface Detector {
    /** What a detector reports. Calls may arrive on any thread; [ForegroundWake] hops them to main. */
    interface Listener {
        fun onWake(wakeId: String)

        /** The model failed to load. */
        fun onUnavailable()

        fun onCaptureLost(loss: CaptureLoss)
    }

    /** Open the mic and listen (or resume). A mic that won't open, or later dies, arrives as a capture loss. */
    fun start()

    /** Release the mic but keep the model loaded, so the next [start] is instant. Idempotent. */
    fun pause()

    /** Release the mic and unload the model. */
    fun close()
}
