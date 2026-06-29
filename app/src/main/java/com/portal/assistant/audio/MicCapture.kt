package com.portal.assistant.audio

import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession
import com.portal.commons.audio.AudioRecordPcmDevice

/**
 * Captures the handset mic for a conversation turn, delivering 100 ms frames to [onFrame] until [stop].
 *
 * A thin adapter over the shared [PcmCaptureSession] (the robust, never-hanging capture lifecycle) wired to
 * the shared Android mic device [com.portal.commons.audio.AudioRecordPcmDevice]. The session's idle watchdog
 * is enabled ([PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS]): if the slot is stolen / goes half-dead mid-conversation (no frame delivered
 * for that long), the device is rebuilt and the mic reacquired in-place — otherwise the turn would just go
 * silent until the no-speech timer ends it (the reported "assistant silence" symptom). The error-count
 * rebuild is left off; the idle (no-frame) watchdog already covers sustained errors and stalls.
 *
 * Caller must hold RECORD_AUDIO. [onFrame] runs on the capture thread; keep it cheap and non-blocking.
 */
class MicCapture(onFrame: (ByteArray, Int) -> Unit) {

    private val session = PcmCaptureSession(
        device = AudioRecordPcmDevice(),
        onFrame = onFrame,
        onError = { DebugLog.log("mic capture error: $it") },
        log = { DebugLog.log(it) },
        threadName = "assistant-capture",
        idleRebuildMs = PcmCaptureSession.DEFAULT_IDLE_REBUILD_MS,
    )

    fun start() = session.start()

    fun stop() = session.stop()
}
