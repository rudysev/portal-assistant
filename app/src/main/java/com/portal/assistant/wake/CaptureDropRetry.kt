package com.portal.assistant.wake

import com.portal.commons.DebugLog
import com.portal.commons.audio.CaptureLoss

/**
 * [ForegroundWake]'s capture-loss policy: every loss is logged loudly; "dropped" (the mic died under us, e.g. a
 * transient device fault) retries once after [CAPTURE_DROP_RETRY_MS] so wake detection doesn't stay off while on
 * screen; "not granted" (another app holds the single mic slot) waits for the next arm rather than fight for it. Each
 * arm earns one fresh retry, and a retry due after the capture was started or paused again is dropped.
 *
 * Main thread only; the retry runs through [later] (main, after a delay).
 */
internal class CaptureDropRetry(private val later: (delayMs: Long, task: Runnable) -> Unit) {
    private var captureId = 0 // bumped by every start and pause, so a retry due after either one is stale
    private var retried = false // the one drop retry this arm has

    /** A new arm: it earns one fresh retry. */
    fun armed() {
        retried = false
    }

    /** Capture was started or paused: a retry already due is stale. */
    fun captureChanged() {
        captureId++
    }

    /** Logs [loss] and, for the first drop this arm, runs [retry] after the delay unless capture changed meanwhile. */
    fun lost(loss: CaptureLoss, retry: () -> Unit) {
        when (loss) {
            is CaptureLoss.NotGranted ->
                DebugLog.log("FOREGROUND WAKE OFF — capture not granted (${loss.reason}); waiting for the next arm")

            is CaptureLoss.Dropped -> if (retried) {
                DebugLog.log("FOREGROUND WAKE OFF — capture dropped again (${loss.reason}); waiting for the next arm")
            } else {
                retried = true
                DebugLog.log(
                    "FOREGROUND WAKE OFF — capture dropped (${loss.reason}); retrying in ${CAPTURE_DROP_RETRY_MS}ms",
                )
                val lostIn = captureId
                later(CAPTURE_DROP_RETRY_MS) { if (lostIn == captureId) retry() }
            }
        }
    }

    private companion object {
        // Long enough for a transient device fault (the HAL re-routing, a rebuild racing the release) to clear,
        // short enough that a user on screen doesn't notice wake was briefly off.
        const val CAPTURE_DROP_RETRY_MS = 1_000L
    }
}
