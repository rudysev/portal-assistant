package com.portal.assistant.conversation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.portal.assistant.audio.MicCapture
import com.portal.assistant.audio.PcmPlayer
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.tools.ToolRunner
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.DeviceLocation
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.system.NetworkStatus
import com.portal.assistant.ui.RecordingOverlay
import com.portal.assistant.ui.UiVisibility
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
    return AssistantEngine(
        backendChoice = backendChoice,
        clock = MainLooperClock(),
        audio = DeviceAudio(),
        sink = HubSink(setRecording = overlay::setRecording, inForeground = { UiVisibility.inForeground }),
        openingReads = PrefsOpeningReads(appContext),
        toolRunners = { deliver -> ToolRunner.create(appContext, deliver) },
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

/** [EngineAudio] on the handset: a fresh [MicCapture] per open, one [PcmPlayer], the [ListeningTonePlayer]. */
internal class DeviceAudio : EngineAudio {
    private val player = PcmPlayer()
    private var mic: MicCapture? = null

    override fun openMic(onFrame: (pcm: ByteArray, length: Int) -> Unit) {
        mic = MicCapture(onFrame).also { it.start() }
    }

    override fun closeMic() {
        val openMic = mic
        mic = null
        openMic?.stop()
    }

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
