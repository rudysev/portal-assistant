package com.portal.assistant.audio

import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureSession
import com.portal.commons.audio.AudioRecordPcmDevice

/**
 * Captures the handset mic for a conversation turn, delivering 100 ms frames to [onFrame] until [stop].
 *
 * A thin adapter over the shared [PcmCaptureSession] (the robust, never-hanging capture lifecycle) wired to
 * the shared Android mic device [com.portal.commons.audio.AudioRecordPcmDevice]. Deliberately simple: no
 * rebuild — the
 * assistant only captures *after* portal-wake has released the mic (or while foreground), so the slot is
 * free and we don't expect background silencing. If device testing shows otherwise, opt into rebuild via
 * the session's `rebuildAfterReadFailures` (don't assume it).
 *
 * Caller must hold RECORD_AUDIO. [onFrame] runs on the capture thread; keep it cheap and non-blocking.
 */
class MicCapture(onFrame: (ByteArray, Int) -> Unit) {

    private val session = PcmCaptureSession(
        device = AudioRecordPcmDevice(),
        onFrame = onFrame,
        onError = { DebugLog.log("mic open failed: $it") },
        log = { DebugLog.log(it) },
        threadName = "assistant-capture",
    )

    fun start() = session.start()

    fun stop() = session.stop()
}
