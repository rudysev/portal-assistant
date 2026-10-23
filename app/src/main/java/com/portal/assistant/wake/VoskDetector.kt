package com.portal.assistant.wake

import android.content.Context
import com.portal.commons.audio.WakeDetectors
import com.portal.commons.audio.WakeMatcher
import com.portal.commons.audio.WakeMicConfig
import com.portal.commons.audio.WakeMicEngine
import com.portal.commons.audio.WakeWord
import java.io.File

/**
 * The production [Detector]: the commons [WakeMicEngine] running Vosk on the downloaded model, listening for
 * "hey jarvis". Its wake reaches the [Detector.Listener] on the capture thread, its unavailable report and capture
 * loss on main; [ForegroundWake] hops all three.
 */
class VoskDetector(context: Context, modelDir: File, listener: Detector.Listener) : Detector {
    private val engine = WakeMicEngine(
        context = context,
        config = WakeMicConfig(
            wakeWords = listOf(WAKE_WORD),
            detectors = listOf(WakeDetectors.vosk(modelDir = modelDir)),
            onDetectorUnavailable = { listener.onUnavailable() },
            onWake = { event -> listener.onWake(event.wakeId) },
            onCaptureLost = listener::onCaptureLost,
        ),
    )

    override fun start() = engine.start()

    override fun pause() = engine.pause()

    override fun close() = engine.close()

    private companion object {
        /** "hey jarvis" at the strict floor, matching portal-wake's built-in jarvis default so accuracy is identical.
         *  Non-null: the phrase is a valid two-word phrase. */
        val WAKE_WORD =
            checkNotNull(WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = WakeMatcher.STRICT_MIN_CONF)) {
                "built-in phrase \"hey jarvis\""
            }
    }
}
