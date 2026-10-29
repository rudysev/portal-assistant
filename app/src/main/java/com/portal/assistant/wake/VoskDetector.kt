package com.portal.assistant.wake

import android.content.Context
import com.portal.commons.audio.WakeMicConfig
import com.portal.commons.audio.WakeMicEngine
import com.portal.commons.audio.WakeModel
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
            model = WakeModel.Unpacked(modelDir),
            onUnavailable = { listener.onUnavailable() },
            onWake = { event -> listener.onWake(event.wakeId) },
            onCaptureLost = listener::onCaptureLost,
        ),
    )

    override fun start() = engine.start()

    override fun pause() = engine.pause()

    override fun close() = engine.close()

    internal companion object {
        /**
         * The "jarvis" confidence a wake must clear, also declared to portal-wake in the manifest so both detectors
         * judge alike. Genuine wakes on the Portal decode jarvis at 0.98 or above (208 of 211 logged); speakerphone
         * speech across the room decoded a clean "hey jarvis" with jarvis at 0.90, which this rejects.
         */
        const val JARVIS_MIN_CONF = 0.95

        /** "hey jarvis" at [JARVIS_MIN_CONF]. Non-null: the phrase is a valid two-word phrase. */
        val WAKE_WORD =
            checkNotNull(WakeWord.fromPhrase("hey jarvis", id = "jarvis", minConf = JARVIS_MIN_CONF)) {
                "built-in phrase \"hey jarvis\""
            }
    }
}
