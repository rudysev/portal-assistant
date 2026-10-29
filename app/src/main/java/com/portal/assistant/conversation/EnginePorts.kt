package com.portal.assistant.conversation

import com.portal.assistant.system.DeviceLocation
import java.time.ZonedDateTime

/*
 * The role ports [AssistantEngine] drives its I/O through, so the engine runs on the JVM under test with recording
 * fakes. Production adapters live in AndroidEnginePorts.kt and are wired by [AssistantEngine.create]. The voice
 * backend (via [com.portal.assistant.conversation.backend.Backends.Choice]) and the tool runner are the other two
 * seams.
 */

/**
 * The engine's one orchestration thread and its one clock. Every event is posted here, and both client timers
 * (no-speech, stall) run on it.
 */
interface EngineClock {
    /** Monotonic milliseconds: the one clock for mic frames, the Listening tone, the no-speech grace and stall logs. */
    fun now(): Long

    fun post(task: Runnable)

    fun postDelayed(task: Runnable, delayMs: Long)

    fun cancel(task: Runnable)
}

/** The conversation's mic, its player, the Listening tone and the hush on other apps' audio. */
interface EngineAudio {
    /** Put other apps' audio under the conversation's hush; best effort, a denied hush changes nothing. */
    fun startHush()

    /** Let other apps' audio come back; idempotent, and a no-op if the hush never started or was already lifted. */
    fun liftHush()

    /** Open the mic; [onFrame] runs on the capture thread until [closeMic]. */
    fun openMic(onFrame: (pcm: ByteArray, length: Int) -> Unit)

    fun closeMic()

    /** Start playback. [onDrained] and [onLevel] run on the playback thread. */
    fun startPlayer(onDrained: () -> Unit, onLevel: (level: Float) -> Unit)

    /** Queue 24 kHz PCM for playback; called on the socket thread. */
    fun enqueue(pcm24k: ByteArray)

    /** Drop everything not yet played. */
    fun flushPlayback()

    /** Bytes played so far this conversation (0 before [startPlayer]). */
    fun playedBytes(): Long

    fun stopPlayer()

    /** Play the Listening tone; exactly one callback runs, off the caller's thread, timed on [EngineClock.now]. */
    fun playListeningTone(onStarted: (startedAtMs: Long) -> Unit, onFailed: () -> Unit)
}

/** Where the conversation shows itself: the [ConversationHub] writes and the orange bar. */
interface ConversationSink {
    fun startFresh()

    fun startResume()

    fun setPhase(phase: ConversationHub.UiPhase)

    fun setTurns(turns: List<Turn>)

    /** Called on the capture and playback threads. */
    fun setAudioLevel(level: Float)

    fun postNotice(message: String)

    fun markIdle()

    /** The orange bar at [level]; the sink decides whether it shows (only with the app in the background). */
    fun showRecordingBar(level: Float)

    fun hideRecordingBar()
}

/** What a conversation reads to open, and the online check behind a disconnect notice. */
interface OpeningReads {
    fun modelId(): String

    /** A Search key is set. */
    fun searchKeyPresent(): Boolean

    /** The Chosen speaker, or null for Server default. */
    fun speakerId(): String?

    fun listeningToneEnabled(): Boolean

    fun localTime(): ZonedDateTime

    fun locationOverride(): String?

    fun deviceLocation(): DeviceLocation?

    fun onScreenTurns(): List<Turn>

    fun isOnline(): Boolean
}
