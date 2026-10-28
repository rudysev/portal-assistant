package com.portal.assistant.conversation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.portal.assistant.audio.AudioFocusHush
import com.portal.assistant.audio.Hush
import com.portal.assistant.audio.PcmPlayer
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.tools.ToolRunner
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.DeviceLocation
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.system.NetworkStatus
import com.portal.assistant.ui.RecordingOverlay
import com.portal.assistant.ui.UiVisibility
import com.portal.commons.CapturePolicy
import com.portal.commons.DebugLog
import com.portal.commons.PcmCapture
import com.portal.commons.audio.AudioRecordPcmDevice
import com.portal.commons.audio.CaptureLoss
import java.time.ZoneId
import java.time.ZonedDateTime

/** The production engine: the main looper, the handset's audio, the Hub and orange bar, the app's settings. */
fun AssistantEngine.Companion.create(
    context: Context,
    backendChoice: Backends.Choice,
    onEnded: () -> Unit,
): AssistantEngine {
    val appContext = context.applicationContext
    val overlay = RecordingOverlay(appContext)
    // This conversation's one hush: the engine starts and lifts it through its audio port; its tools see it too.
    val hush = AudioFocusHush(appContext)
    return AssistantEngine(
        backendChoice = backendChoice,
        clock = MainLooperClock(),
        audio = DeviceAudio(hush),
        sink = HubSink(setRecording = overlay::setRecording, inForeground = { UiVisibility.inForeground }),
        openingReads = PrefsOpeningReads(appContext),
        toolRunners = { deliver -> ToolRunner.create(appContext, deliver, hush) },
        onEnded = onEnded,
    )
}

/** [EngineClock] on the main looper, on [SystemClock.elapsedRealtime] — the clock the tone player reports in. */
internal class MainLooperClock : EngineClock {
    private val handler = Handler(Looper.getMainLooper())

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun cancel(task: Runnable) = handler.removeCallbacks(task)
}

/**
 * [EngineAudio] on the handset: one [PcmCapture] for the port's lifetime, one [PcmPlayer], the [ListeningTonePlayer],
 * and the conversation's [hush].
 *
 * Each [openMic] starts a capture request with a listener bound to that open's frame handler, so frames from an older
 * request's capture thread never reach a newer one; [closeMic] pauses it, freeing the mic slot before it returns.
 *
 * The capture's idle watchdog is on ([CapturePolicy.DEFAULT_IDLE_REBUILD_MS]): if the slot is stolen or goes half-dead
 * mid-conversation (no frame for that long), the device is rebuilt and the mic reacquired in place, otherwise the turn
 * would go silent until the no-speech timer ends it. The read-error rebuild is off; the idle watchdog already covers
 * sustained errors and stalls.
 *
 * Digital silence rebuilds too: on Portal+ gen1 the system silences our open input when the app leaves the foreground
 * mid-conversation (Home pressed), and only a reopened mic hears again. [SILENCE_WINDOW_MS] is kept short so the
 * user's first words after Home aren't lost.
 *
 * A capture that dies on its own (the mic won't open, a rebuild fails, the stream stays digitally silent after its
 * rebuild) is only logged with its [CaptureLoss] cause; the turn then goes quiet until the no-speech timer ends it.
 *
 * Caller must hold RECORD_AUDIO. The frame handler runs on the capture thread; keep it cheap and non-blocking.
 */
internal class DeviceAudio(private val hush: Hush) : EngineAudio {
    private val player = PcmPlayer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mic = PcmCapture(
        newDevice = ::AudioRecordPcmDevice,
        postToMain = { mainHandler.post(it) },
        log = { DebugLog.log(it) },
        threadName = "assistant-capture",
        policy = CapturePolicy(
            idleRebuildMs = CapturePolicy.DEFAULT_IDLE_REBUILD_MS,
            silence = CapturePolicy.Silence(SILENCE_WINDOW_MS, rebuildFirst = true),
        ),
    )

    override fun startHush() = hush.start()

    override fun liftHush() = hush.lift()

    override fun openMic(onFrame: (pcm: ByteArray, length: Int) -> Unit) {
        // Named apart from the listener's own onFrame, so the override below plainly forwards rather than recurses.
        val deliverFrame = onFrame
        mic.start(
            object : PcmCapture.Listener {
                override fun onFrame(buf: ByteArray, n: Int) = deliverFrame(buf, n)

                // A data class: logs the cause and its reason, e.g. "Dropped(reason=…)".
                override fun onLost(loss: CaptureLoss) = DebugLog.log("mic capture lost: $loss")
            },
        )
    }

    override fun closeMic() = mic.pause()

    override fun startPlayer(onDrained: () -> Unit, onLevel: (level: Float) -> Unit) {
        player.start()
        player.setOnDrained(onDrained)
        player.setOnLevel(onLevel)
    }

    override fun enqueue(pcm24k: ByteArray) = player.enqueue(pcm24k)

    override fun flushPlayback() = player.flush()

    override fun playedBytes(): Long = player.playedBytes()

    override fun stopPlayer() = player.stop()

    override fun playListeningTone(onStarted: (startedAtMs: Long) -> Unit, onFailed: () -> Unit) =
        ListeningTonePlayer.play(onStarted, onFailed)

    private companion object {
        // Real mics never deliver this long a run of exact zeros (the longest seen on Portal+ gen1 was ~137 ms; see
        // portal-apps ADR 0002), so a run this long is the system silencing us.
        const val SILENCE_WINDOW_MS = 500L
    }
}

/**
 * [ConversationSink] onto the process-wide [ConversationHub], and the orange bar through [setRecording] — shown only
 * while [inForeground] is false (the in-app UI replaces it when the app is open).
 */
internal class HubSink(
    private val setRecording: (recording: Boolean, level: Float) -> Unit,
    private val inForeground: () -> Boolean,
) : ConversationSink {
    override fun startFresh() = ConversationHub.startFresh()

    override fun startResume() = ConversationHub.startResume()

    override fun setPhase(phase: ConversationHub.UiPhase) = ConversationHub.setPhase(phase)

    override fun setTurns(turns: List<Turn>) = ConversationHub.setTurns(turns)

    override fun setAudioLevel(level: Float) = ConversationHub.setAudioLevel(level)

    override fun postNotice(message: String) = ConversationHub.postNotice(message)

    override fun markIdle() = ConversationHub.markIdle()

    override fun showRecordingBar(level: Float) = setRecording(!inForeground(), level)

    override fun hideRecordingBar() = setRecording(false, 0f)
}

/** [OpeningReads] from [AppPrefs], [LocationProvider], the on-screen [ConversationHub] turns and [NetworkStatus]. */
internal class PrefsOpeningReads(private val appContext: Context) : OpeningReads {
    override fun modelId(): String = AppPrefs.modelId(appContext)

    override fun speakerId(): String? = AppPrefs.speakerId(appContext)

    override fun listeningToneEnabled(): Boolean = AppPrefs.listeningToneEnabled(appContext)

    override fun localTime(): ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())

    override fun locationOverride(): String? = LocationProvider.override(appContext)

    override fun deviceLocation(): DeviceLocation? = LocationProvider.current(appContext)

    override fun onScreenTurns(): List<Turn> = ConversationHub.session.value.turns

    override fun isOnline(): Boolean = NetworkStatus.isOnline(appContext)
}
