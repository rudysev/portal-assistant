package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory
import com.portal.assistant.conversation.tools.ToolSet
import com.portal.assistant.system.DeviceLocation
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/*
 * Recording fakes for [AssistantEngineTest]. Everything runs on the test thread: the clock's posted tasks and timers
 * run only when the test steps them, and the backend and mic call the engine synchronously, playing the socket and
 * capture threads. The fakes that do I/O append to one shared [log] so a test can assert the order across them.
 */

/** The engine's thread and clock, stepped by hand: [runPosted] drains the posts, [advanceTo] moves time. */
class SteppedClock(var nowMs: Long) : EngineClock {
    private class Timer(val dueAtMs: Long, val task: Runnable)

    private val posted = ArrayDeque<Runnable>()
    private val timers = mutableListOf<Timer>()

    override fun now(): Long = nowMs

    override fun post(task: Runnable) {
        posted.addLast(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        timers += Timer(nowMs + delayMs, task)
    }

    override fun cancel(task: Runnable) {
        posted.removeAll { it === task }
        timers.removeAll { it.task === task }
    }

    /** When each pending timer is due. */
    val timersDueAt: List<Long> get() = timers.map { it.dueAtMs }.sorted()

    fun runPosted() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    /** Run what is posted, then every timer due by [targetMs] in due order (and what each posts), ending at it. */
    fun advanceTo(targetMs: Long) {
        runPosted()
        while (true) {
            val next = timers.filter { it.dueAtMs <= targetMs }.minByOrNull { it.dueAtMs } ?: break
            timers.remove(next)
            nowMs = next.dueAtMs
            next.task.run()
            runPosted()
        }
        nowMs = targetMs
    }

    fun advanceBy(deltaMs: Long) = advanceTo(nowMs + deltaMs)
}

/** The mic, player and tone. [frame] plays the capture thread; [drained] and [played] the playback thread. */
class FakeAudio(private val log: MutableList<String>) : EngineAudio {
    private var onFrame: ((ByteArray, Int) -> Unit)? = null
    private var onDrained: (() -> Unit)? = null
    private var onLevel: ((Float) -> Unit)? = null
    private var toneStarted: ((Long) -> Unit)? = null
    var playedBytes = 0L
    val enqueued = mutableListOf<ByteArray>()
    var flushes = 0

    override fun openMic(onFrame: (pcm: ByteArray, length: Int) -> Unit) {
        log += "mic open"
        this.onFrame = onFrame
    }

    override fun closeMic() {
        log += "mic close"
        onFrame = null
    }

    override fun startPlayer(onDrained: () -> Unit, onLevel: (level: Float) -> Unit) {
        log += "player start"
        this.onDrained = onDrained
        this.onLevel = onLevel
    }

    override fun enqueue(pcm24k: ByteArray) {
        enqueued += pcm24k
    }

    override fun flushPlayback() {
        flushes++
    }

    override fun playedBytes(): Long = playedBytes

    override fun stopPlayer() {
        log += "player stop"
    }

    override fun playListeningTone(onStarted: (startedAtMs: Long) -> Unit, onFailed: () -> Unit) {
        log += "tone"
        toneStarted = onStarted
    }

    /** One captured frame, if the mic is open. */
    fun frame(pcm: ByteArray) {
        onFrame?.invoke(pcm, pcm.size)
    }

    /** The player played [bytes] more and its queue drained. */
    fun drained(bytes: Long = 0L) {
        playedBytes += bytes
        onLevel?.invoke(0f)
        onDrained?.invoke()
    }

    companion object {
        /** A 100 ms frame loud enough to count as speech once gained. */
        val LOUD = pcmOf(sample = 3_000)

        /** A 100 ms frame of silence. */
        val QUIET = pcmOf(sample = 0)

        private fun pcmOf(sample: Int): ByteArray {
            val samples = 1_600
            return ByteArray(samples * 2) { i -> if (i % 2 == 0) sample.toByte() else (sample shr 8).toByte() }
        }
    }
}

/** Records what the engine shows: the Hub writes and the orange bar. */
class RecordingSink(private val log: MutableList<String>) : ConversationSink {
    val starts = mutableListOf<String>()
    val phases = mutableListOf<ConversationHub.UiPhase>()
    val notices = mutableListOf<String>()
    var turnsShown: List<Turn> = emptyList()
        private set
    var turnWrites = 0
    var idle = false

    /** Every bar call: `show <level>` or `hide`. */
    val bar = mutableListOf<String>()

    override fun startFresh() {
        starts += "fresh"
    }

    override fun startResume() {
        starts += "resume"
    }

    override fun setPhase(phase: ConversationHub.UiPhase) {
        if (phases.lastOrNull() != phase) phases += phase
    }

    override fun setTurns(turns: List<Turn>) {
        turnsShown = turns
        turnWrites++
    }

    override fun setAudioLevel(level: Float) = Unit

    override fun postNotice(message: String) {
        notices += message
    }

    override fun markIdle() {
        log += "hub idle"
        idle = true
    }

    override fun showRecordingBar(level: Float) {
        bar += "show $level"
    }

    override fun hideRecordingBar() {
        log += "bar hide"
        bar += "hide"
    }
}

/** Scripted settings and device state. */
class FakeOpeningReads : OpeningReads {
    var listeningToneEnabled = false
    var onScreenTurns: List<Turn> = emptyList()
    var online = true

    override fun modelId(): String = "gemini-2.5-flash-native-audio-latest"

    override fun speakerId(): String? = null

    override fun listeningToneEnabled(): Boolean = listeningToneEnabled

    override fun localTime(): ZonedDateTime = ZonedDateTime.of(2026, 10, 23, 9, 30, 0, 0, ZoneId.of("UTC"))

    override fun locationOverride(): String? = null

    override fun deviceLocation(): DeviceLocation? = null

    override fun onScreenTurns(): List<Turn> = onScreenTurns

    override fun isOnline(): Boolean = online
}

/** Builds [FakeBackend]s and keeps each one, with the config it was built from. */
class FakeBackendFactory(private val log: MutableList<String>, override val deadAirStallMs: Long) :
    VoiceBackendFactory {
    val built = mutableListOf<FakeBackend>()

    override fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend =
        FakeBackend(config, listener, log).also { built += it }
}

/** Records what the engine sends; [emit] plays the socket thread. */
class FakeBackend(
    val config: BackendConfig,
    private val listener: VoiceBackend.Listener,
    private val log: MutableList<String>,
) : VoiceBackend {
    val sentAudio = mutableListOf<ByteArray>()
    val sentTexts = mutableListOf<String>()
    val toolResponses = mutableListOf<List<ToolResult>>()

    override fun connect() {
        log += "connect"
    }

    override fun sendAudio(pcm: ByteArray) {
        sentAudio += pcm
    }

    override fun sendText(text: String) {
        sentTexts += text
    }

    override fun sendToolResponse(results: List<ToolResult>) {
        toolResponses += results
    }

    override fun close() {
        log += "backend close"
    }

    fun emit(event: BackendEvent) = listener.onEvent(event)
}

/** Tools that answer `{"ok": <name>}` and record what they were asked. */
class FakeToolSet(private val log: MutableList<String>) : ToolSet {
    val invoked = mutableListOf<String>()

    override fun declarations(): List<JSONObject> = listOf(JSONObject().put("name", "portal.get_time"))

    override fun externalPromptLines(): List<String> = emptyList()

    override fun invoke(name: String, args: JSONObject): JSONObject {
        invoked += name
        return JSONObject().put("ok", name)
    }

    override fun dispose() {
        log += "tools disposed"
    }
}

/** The `tool-exec` thread: a call runs the moment it is handed over, unless [holding] keeps it queued unstarted. */
class FakeToolExecutor : AbstractExecutorService() {
    private var shutdown = false
    var holding = false
    val queued = mutableListOf<Runnable>()

    override fun execute(command: Runnable) {
        when {
            shutdown -> Unit
            holding -> queued += command
            else -> command.run()
        }
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): List<Runnable> {
        shutdown = true
        return emptyList()
    }

    override fun isShutdown() = shutdown

    override fun isTerminated() = shutdown

    override fun awaitTermination(timeout: Long, unit: TimeUnit) = shutdown
}
