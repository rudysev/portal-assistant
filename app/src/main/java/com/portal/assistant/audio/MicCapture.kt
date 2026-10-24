package com.portal.assistant.audio

import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession
import com.portal.commons.audio.AudioRecordPcmDevice

/**
 * Captures the handset mic for a conversation turn, delivering 100 ms frames to [onFrame] until [stop].
 *
 * A thin adapter over the shared [PcmCaptureSession] (the robust, never-hanging capture lifecycle) wired to
 * the shared Android mic device [com.portal.commons.audio.AudioRecordPcmDevice]. The session's idle watchdog
 * is enabled ([PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS]): if the slot is stolen / goes half-dead mid-conversation (no
 * frame delivered
 * for that long), the device is rebuilt and the mic reacquired in-place — otherwise the turn would just go
 * silent until the no-speech timer ends it (the reported "assistant silence" symptom). The error-count
 * rebuild is left off; the idle (no-frame) watchdog already covers sustained errors and stalls.
 *
 * Digital silence rebuilds too: on Portal+ gen1 the system silences our open input when the app leaves the foreground
 * mid-conversation (Home pressed), and only a reopened mic hears again. [SILENCE_WINDOW_MS] is kept short so the
 * user's first words after Home aren't lost.
 *
 * A capture that dies on its own (the mic won't open, a rebuild fails, the stream stays digitally silent after its
 * rebuild) is logged with its [com.portal.commons.audio.CaptureLoss] cause; the turn then goes quiet until the
 * no-speech timer ends it.
 * One instance captures once: [start] after [stop] is a no-op, so build a new one per capture.
 *
 * Caller must hold RECORD_AUDIO. [onFrame] runs on the capture thread; keep it cheap and non-blocking.
 */
class MicCapture(onFrame: (ByteArray, Int) -> Unit) {

    private val session = PcmCaptureSession(
        device = AudioRecordPcmDevice(),
        onFrame = onFrame,
        // A data class: logs the cause and its reason, e.g. "Dropped(reason=…)".
        onLost = { DebugLog.log("mic capture lost: $it") },
        log = { DebugLog.log(it) },
        threadName = "assistant-capture",
        idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
        silenceWindowMs = SILENCE_WINDOW_MS,
        rebuildOnSilence = true,
    )

    fun start() {
        session.start()
    }

    fun stop() {
        session.stop()
    }

    private companion object {
        // Real mics never deliver this long a run of exact zeros (the longest seen on Portal+ gen1 was ~137 ms; see
        // portal-apps ADR 0002), so a run this long is the system silencing us.
        const val SILENCE_WINDOW_MS = 500L
    }
}
