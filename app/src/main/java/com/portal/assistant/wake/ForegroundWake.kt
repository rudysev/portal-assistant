package com.portal.assistant.wake

import com.portal.commons.DebugLog
import java.io.File
import java.util.concurrent.Executor

/**
 * **Foreground wake**: Jarvis listening for "hey jarvis" itself — gen2 only (Android 10 silences a background mic,
 * so portal-wake can't hand off there) and only while the app is on screen. Owns the whole lifecycle: the
 * [Detector], the one-time [WakeModel] download, the setup status and the retry/give-up rules. The host only tells
 * it what happened (the five calls) and gets a wake back through [onWake].
 *
 * It may arm only when [supported], not given up, on screen, not in a conversation and the mic is permitted. Arming
 * downloads the model first if it isn't installed (once at a time, skipped quietly offline); the detector is built on
 * the first arm and kept, paused not closed, between arms so re-arming is instant. Giving up — after
 * [MAX_MODEL_DOWNLOAD_ATTEMPTS] failed downloads, or a model that still won't load after one fresh copy — lasts for
 * the process, so a doomed setup doesn't re-fetch or grab the mic on every foreground.
 *
 * Every call and both callbacks run on the main thread: the download runs on [io], and its progress and result, like
 * the detector's callbacks, come back through [main].
 */
class ForegroundWake(
    private val supported: Boolean,
    private val micPermitted: () -> Boolean,
    private val online: () -> Boolean,
    private val model: WakeModel,
    private val detectors: (modelDir: File, onWake: (wakeId: String) -> Unit, onUnavailable: () -> Unit) -> Detector,
    private val io: Executor,
    private val main: Executor,
    private val onWake: (wakeId: String) -> Unit,
    private val onSetup: (ModelSetup) -> Unit,
) {
    private var onScreen = false
    private var inConversation = false
    private var closed = false
    private var detector: Detector? = null
    private var downloading = false
    private var failedDownloads = 0
    private var reloadTried = false // the delete + re-download recovery runs once per process
    private var gaveUp = false

    private val mayArm get() = supported && !closed && !gaveUp && onScreen && !inConversation

    fun foregrounded() {
        onScreen = true
        armIfAllowed()
    }

    /** Releases the mic (so portal-wake can reclaim it) but keeps the model loaded. */
    fun backgrounded() {
        onScreen = false
        detector?.pause()
    }

    /** The conversation is about to open its mic — the single slot — so stop listening first. */
    fun conversationStarting() {
        inConversation = true
        detector?.pause()
    }

    /** A conversation ended, or a Trigger was refused before one opened: re-arm if still on screen. */
    fun conversationEnded() {
        inConversation = false
        armIfAllowed()
    }

    fun close() {
        closed = true
        detector?.close()
        detector = null
    }

    private fun armIfAllowed() {
        if (!mayArm) return
        when {
            // Don't load the model for a capture that can only fail. The first conversation is what asks for the
            // mic, so its end re-arms.
            !micPermitted() -> DebugLog.log("foreground detection skipped — RECORD_AUDIO not granted yet")

            !model.isInstalled() -> download()

            else -> arm()
        }
    }

    private fun arm() {
        val armed = detector ?: detectors(
            model.dir,
            { wakeId -> main.execute { heard(wakeId) } },
            { main.execute { modelFailedToLoad() } },
        ).also { detector = it }
        // False only when a prior capture thread is wedged and the rebuild-retry was also refused — rare, but
        // logged so a silently-off detector is diagnosable from debug.txt.
        if (!armed.start()) DebugLog.log("foreground detector start refused (capture wedged)")
    }

    private fun heard(wakeId: String) {
        if (closed) return
        detector?.pause()
        onWake(wakeId)
    }

    /**
     * The installed model failed to load: corrupt, or an incompatible format. Close the failed detector (it opened the
     * mic to buffer while loading), delete the model and re-arm, which fetches a fresh copy — once. If that copy fails
     * too, delete it and give up.
     */
    private fun modelFailedToLoad() {
        if (closed || gaveUp) return
        detector?.close()
        detector = null
        if (reloadTried) {
            gaveUp = true
            model.delete()
            onSetup(ModelSetup.Failed)
            DebugLog.log("wake model load failed again — giving up foreground detection this session")
            return
        }
        reloadTried = true
        DebugLog.log("wake model load failed → deleting for re-download")
        model.delete()
        armIfAllowed()
    }

    /** The model isn't in the APK (dead weight on gen1): fetch it once, then arm if still allowed. */
    private fun download() {
        if (downloading) return
        // Offline isn't a failed attempt: a later foreground with network still downloads.
        if (!online()) {
            DebugLog.log("wake model not downloaded and offline — foreground detection off for now")
            return
        }
        downloading = true
        DebugLog.log("wake model missing → downloading (gen2 first-run)")
        onSetup(ModelSetup.Downloading(0f))
        io.execute {
            val ok = model.install { progress -> main.execute { onSetup(ModelSetup.Downloading(progress)) } }
            main.execute {
                downloading = false
                if (ok) {
                    failedDownloads = 0
                    onSetup(ModelSetup.Idle)
                    armIfAllowed()
                } else {
                    onSetup(ModelSetup.Failed)
                    if (++failedDownloads >= MAX_MODEL_DOWNLOAD_ATTEMPTS) {
                        gaveUp = true
                        DebugLog.log("wake model download failed $failedDownloads× — giving up this session")
                    }
                }
            }
        }
    }

    private companion object {
        // Failed downloads tolerated per process: a transient network blip gets a few foreground retries, a
        // permanently failing install (bad layout, silently truncated response) stops re-fetching.
        const val MAX_MODEL_DOWNLOAD_ATTEMPTS = 3
    }
}
