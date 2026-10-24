package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendError
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.tools.ToolRunner
import com.portal.commons.DebugLog
import java.util.concurrent.Executor

/**
 * The impure shell around the pure [reduce] state machine: it owns the [VoiceBackend], the [ToolRunner] and the two
 * timers, drives the mic, player and tone ([EngineAudio]) and the UI ([ConversationSink]), and runs the conversation
 * as an ongoing multi-turn exchange (Phase 2.b — [MULTI_TURN] = true): after each answer the mic re-opens for a
 * follow-up, and the conversation ends only when the user goes silent (the no-speech timer) or on error. Every mic
 * frame goes through the conversation's [MicGate]. (A "goodbye" fast-exit is deferred to a later milestone.)
 *
 * **Single orchestration thread.** Everything funnels through [dispatch] on the [EngineClock]'s thread (the main
 * looper in production): the backend callbacks (e.g. OkHttp thread), the player's drain (writer thread), and the
 * timer Runnables all post an [Event] here. So state is touched on one thread only — no locks, no CAS. The only
 * thing off this thread is the hot audio path: mic frames go straight to the socket and audio chunks straight to
 * the player (both thread-safe), to keep latency low.
 *
 * Lifecycle: [start] connects + opens the mic; the conversation ends itself (the user goes silent or
 * an error) via the [Action.End] action, which tears everything down and calls [onEnded] so the
 * service can return to standby (mic released, so portal-wake reclaims by detection).
 *
 * Every collaborator comes in through the constructor (the role ports in EnginePorts.kt, the backend through
 * [backendChoice], the [ToolRunner] through [toolRunners]); `AssistantEngine.create` (AndroidEnginePorts.kt) wires
 * the production adapters.
 */
// The one impure shell: a function per lifecycle step, Action and hot-path callback. The decisions already live in
// the pure reducer, MicGate, Transcript and ToolRunner; what is left here is the wiring between them. Every
// collaborator is an injected port or seam so the unit tests drive it without Android; the factory passes them by name.
@Suppress("TooManyFunctions", "LongParameterList")
class AssistantEngine(
    private val backendChoice: Backends.Choice,
    private val clock: EngineClock,
    private val audio: EngineAudio,
    private val sink: ConversationSink,
    private val openingReads: OpeningReads,
    private val toolRunners: (deliver: Executor) -> ToolRunner,
    private val onEnded: () -> Unit,
) {
    private val stallMs: Long = backendChoice.factory.deadAirStallMs

    // This conversation's tools, their after-speech effects and each batch's bookkeeping; results come back on
    // the engine's thread. Built in [start] once the trigger isn't refused; disposed on teardown.
    private var toolRunner: ToolRunner? = null

    private var backend: VoiceBackend? = null

    private var state = ConversationState()

    /** The on-screen transcript + paced reveal; advanced only on the clock's thread by [advanceTranscript]. */
    private var transcript = Transcript()

    /**
     * This conversation's [MicGate], built in [start] before the capture thread exists; read here only by the
     * orchestration thread. The capture thread and the tone callbacks are handed the gate itself, so they never read
     * this field or check it for null.
     */
    private var micGate: MicGate? = null

    private var ended = false

    /** Set once the backend session is ready — lets a disconnect read as "lost" vs. "couldn't connect". */
    private var connectedOk = false

    /** A text prompt (e.g. a tapped suggestion) to send as the first user turn, once the socket is ready. */
    private var pendingInitialText: String? = null

    /** [EngineClock.now] of the last audio chunk (set on the OkHttp thread); used to diagnose stalls. */
    @Volatile private var lastAudioAtMs = 0L

    /** [EngineClock.now] of the last LISTENING frame whose gained level cleared the VAD threshold (capture thread). */
    @Volatile private var lastSpeechAtMs = 0L

    /** Consecutive server-unconfirmed no-speech graces granted this listening window (engine thread only). */
    private var noSpeechGraces = 0

    private val noSpeechTimer: Runnable = Runnable {
        // Bounded local-energy grace: if the mic still hears the user at the deadline, defer the end so a
        // follow-up started late in the window survives until the server's transcript arrives (which re-arms
        // via the normal path and resets the budget). The cap bounds noise that never transcribes — see
        // [NoSpeechGrace] and the note in ConversationState's NoSpeechTimeout handling.
        val now = clock.now()
        if (NoSpeechGrace.shouldExtend(now, lastSpeechAtMs, noSpeechGraces)) {
            noSpeechGraces++
            DebugLog.log(
                "no-speech grace $noSpeechGraces/${NoSpeechGrace.MAX_GRACES} " +
                    "(local speech ${now - lastSpeechAtMs}ms ago) — +${NoSpeechGrace.GRACE_MS}ms for server confirm",
            )
            clock.postDelayed(noSpeechTimer, NoSpeechGrace.GRACE_MS) // re-arm WITHOUT resetting the count
        } else {
            dispatch(Event.NoSpeechTimeout)
        }
    }

    private val stallTimer = Runnable {
        // Dead-air watchdog: the reducer arms this only via stallDecision (idle + no tools, plus the
        // LISTENING→SPEAKING entry arms) — so reaching here is a genuine stall; no defer needed.
        // Diagnose: thinking gap before any audio, or audio that started then stopped?
        val gap = if (lastAudioAtMs == 0L) -1 else clock.now() - lastAudioAtMs
        val modelChars = transcript.turns.lastOrNull()?.text?.length ?: 0
        DebugLog.log(
            "stall fired: ${gap}ms since last audio, turnComplete=${state.turnComplete}, modelChars=$modelChars",
        )
        dispatch(Event.StallTimeout)
    }

    /**
     * Open the conversation for [trigger]: refuse it if the backend has no credential, else build the tool runner,
     * snapshot the inputs, let [ConversationOpening] decide the opening, then connect and open the mic. Settings are
     * read here once; nothing after the snapshot reads [OpeningReads] (bar the online check behind a disconnect
     * notice). Multi-turn *within* this conversation is the live backend session, not replayed context.
     *
     * @return null once started, or the [Refusal] (no credential) — then nothing was built, opened or published (no
     * tool runner, no connecting flash), [onEnded] is never called, and the caller posts the notice.
     */
    fun start(trigger: Trigger): Refusal? {
        ConversationOpening.refusalFor(backendChoice)?.let { refusal ->
            DebugLog.log("no credential → cannot start (kind=${backendChoice.kind})")
            ended = true
            return refusal
        }
        val conversationTools = toolRunners(Executor { clock.post(it) })
        toolRunner = conversationTools
        val inputs = OpeningInputs(
            backendChoice = backendChoice,
            modelId = openingReads.modelId(),
            speakerId = openingReads.speakerId(),
            listeningToneEnabled = openingReads.listeningToneEnabled(),
            now = openingReads.localTime(),
            locationOverride = openingReads.locationOverride(),
            deviceLocation = openingReads.deviceLocation(),
            onScreenTurns = openingReads.onScreenTurns(),
            toolDeclarations = conversationTools.declarations(),
            externalPromptLines = conversationTools.externalPromptLines(),
        )
        val opening = ConversationOpening.open(trigger, inputs)
        // Sent as the first user turn once the socket is ready (a tapped suggestion); see onBackendEvent.
        pendingInitialText = opening.firstText
        when (val seed = opening.transcriptSeed) {
            is TranscriptSeed.Resume -> {
                transcript = Transcript(turns = seed.turnsShown)
                DebugLog.log(
                    "engine start (resume, ${seed.turnsShown.size} turns, ${seed.turnsReplayed.size} replayed)",
                )
                sink.startResume()
            }

            TranscriptSeed.Fresh -> {
                DebugLog.log("engine start")
                transcript = Transcript()
                sink.startFresh()
            }
        }
        // Lead with the two real-latency operations — the WebSocket handshake (the connect long pole) and the
        // AudioRecord open — so the synchronous player setup below (~27 ms of AudioTrack alloc + writer thread)
        // overlaps the network round-trip instead of delaying it. Playback isn't needed until the model speaks
        // (long after Ready), and nothing reads the player before it's started — no audio can arrive pre-Ready,
        // onFrame never touches it — so starting it after connect()/mic is safe.
        backend = backendChoice.factory.create(opening.backendConfig, backendListener).also { it.connect() }
        if (!opening.listeningTonePlays) {
            DebugLog.log(
                "listening tone: skipped (enabled=${inputs.listeningToneEnabled}, voiceQuery=${opening.voiceQuery})",
            )
        }
        // Built before mic.start() spawns the capture thread, so its initial state is visible to it. With the tone
        // on, the gate starts with it pending: from the first frame nothing is sent until the tone has started.
        val gate = MicGate(opening.voiceQuery, opening.listeningTonePlays).also { micGate = it }
        audio.openMic { pcm, length -> onFrame(gate, pcm, length) }
        if (opening.listeningTonePlays) playListeningTone(gate)
        audio.startPlayer(
            onDrained = { clock.post { dispatch(Event.PlaybackIdle) } },
            onLevel = { level ->
                sink.setAudioLevel(level) // drives the speaking visualizer
                // The writer thread: hop to the engine's thread, the transcript's only thread, to pace the reveal.
                clock.post { advanceTranscript(Transcript.Event.AudioPlayed(audio.playedBytes())) }
            },
        )
        return null
    }

    /**
     * Play the Listening tone at the mic open, with the [gate] already holding it pending. Async: the mic and connect
     * are already under way and don't wait on it. Its start hands the gate the suppression window; a failure to play
     * ends the wait, so the conversation is sent as if the tone were off.
     */
    private fun playListeningTone(gate: MicGate) {
        val micOpenedAtMs = clock.now()
        audio.playListeningTone(
            onStarted = { startedAtMs ->
                gate.toneStarted(startedAtMs)
                DebugLog.log("listening tone: playing (+${startedAtMs - micOpenedAtMs}ms after mic open)")
            },
            onFailed = {
                gate.toneFailed()
                DebugLog.log("listening tone: didn't play; mic frames are sent again")
            },
        )
    }

    /** Tear down without firing [onEnded] (the service is already stopping). Idempotent. */
    fun stop() {
        if (ended) return
        ended = true
        teardown()
    }

    // ---- hot audio path (off the orchestration thread) ---------------------------------------------

    /** Capture thread. The [MicGate] decides what this frame sends, shows and logs; this only applies its outcome. */
    private fun onFrame(gate: MicGate, pcm: ByteArray, length: Int) {
        val outcome = gate.onFrame(pcm, length, clock.now())
        outcome.logLines.forEach(DebugLog::log)
        outcome.audioToSend.forEach { audio -> backend?.sendAudio(audio) }
        // Local speech signal for the no-speech grace, on the same clock its timer reads.
        if (outcome.speechHeard) lastSpeechAtMs = clock.now()
        // The orange bar is the *backgrounded* mic indicator; the sink hides it while the app is open, where the in-UI
        // state replaces it.
        when (val indicator = outcome.indicator) {
            MicGate.Indicator.None -> Unit

            MicGate.Indicator.BarAtRest -> sink.showRecordingBar(level = 0f)

            is MicGate.Indicator.Level -> {
                sink.setAudioLevel(indicator.level) // drives the in-app listening visualizer
                sink.showRecordingBar(indicator.level)
            }
        }
    }

    /**
     * Socket thread. Audio is enqueued on the player right here (latency) before anything is posted; every event,
     * audio included, then goes to [onBackendEvent] on the engine's thread.
     */
    private val backendListener = VoiceBackend.Listener { event ->
        when (event) {
            is BackendEvent.Audio -> {
                lastAudioAtMs = clock.now()
                audio.enqueue(event.pcm24k)
            }

            is BackendEvent.Failed -> DebugLog.log("backend error: ${event.error}")

            is BackendEvent.ClosingSoon -> DebugLog.log("backend closing soon (grace=${event.graceMs}ms)")

            else -> Unit
        }
        clock.post { onBackendEvent(event) }
    }

    /**
     * Engine's thread. Every reaction to a [BackendEvent] is here: what it adds to the transcript (applied first), the
     * reducer [Event] it dispatches, and the engine's own bookkeeping — [connectedOk] and the pending first text on
     * ready, the notice on a failure or close.
     */
    private fun onBackendEvent(event: BackendEvent) {
        val playedBytes = audio.playedBytes()
        when (event) {
            BackendEvent.Ready -> onReady()

            is BackendEvent.InputTranscript -> {
                advanceTranscript(Transcript.Event.UserText(event.text))
                dispatch(Event.UserSpeaking)
            }

            is BackendEvent.OutputTranscript -> {
                advanceTranscript(Transcript.Event.JarvisText(event.text, state.phase))
                // Model activity only while already SPEAKING: a belated frame after listening restarted must not enter
                // SPEAKING (and mute the mic) on its own.
                if (state.phase == Phase.SPEAKING) dispatch(Event.ModelActivity)
            }

            is BackendEvent.Audio -> {
                advanceTranscript(Transcript.Event.AudioReceived(event.pcm24k.size, playedBytes))
                dispatch(Event.PlaybackBusy)
            }

            BackendEvent.ModelGenerating -> dispatch(Event.ModelActivity)

            BackendEvent.TurnComplete -> dispatch(Event.TurnComplete)

            BackendEvent.Interrupted -> {
                advanceTranscript(Transcript.Event.Interrupted(playedBytes))
                dispatch(Event.Interrupted)
            }

            is BackendEvent.ToolCall -> dispatch(Event.ToolCallReceived(event.calls))

            is BackendEvent.ToolCancel -> dispatch(Event.ToolCancelled(event.ids))

            // The close that follows ends the conversation.
            is BackendEvent.ClosingSoon -> Unit

            is BackendEvent.Failed -> {
                postDisconnectNotice(event.error)
                dispatch(Event.Disconnected)
            }

            BackendEvent.Closed -> {
                postDisconnectNotice(backendError = null)
                dispatch(Event.Disconnected)
            }
        }
    }

    /** The session is ready: dispatch it, then send a tapped suggestion as the first turn now that we're LISTENING. */
    private fun onReady() {
        connectedOk = true
        dispatch(Event.Ready)
        pendingInitialText?.let { text ->
            pendingInitialText = null
            sendInitialText(text)
        }
    }

    /**
     * A failure or close from the socket. A *normal* end (user went silent) sets [ended] before it closes the
     * socket, so reaching here with `ended == false` means the connection dropped on us unexpectedly — show a
     * banner before the mapped [Event.Disconnected] tears the conversation down. (When `ended` is already true this
     * is just our own teardown closing the socket; we post nothing and [dispatch] ignores the event.)
     *
     * Double-fire safe: a failure can be followed by a close (a server close reason reports Failed, then Closed).
     * The first posts the notice and ends the turn (so `ended` flips true); the second sees `ended == true` → posts
     * nothing and its dispatch no-ops.
     */
    private fun postDisconnectNotice(backendError: BackendError?) {
        if (!ended) {
            sink.postNotice(userDisconnectNotice(backendError))
        }
    }

    private fun userDisconnectNotice(backendError: BackendError?): String = DisconnectNotice.message(
        backendError = backendError,
        offline = !openingReads.isOnline(),
        connectedOk = connectedOk,
    )

    /**
     * Send a tapped suggestion as the user's first turn. Runs on the engine's thread right after [Event.Ready] put us
     * in LISTENING (so [VoiceBackend.sendText] sees the session ready). We deliberately KEEP the no-speech timer that
     * LISTENING just armed: a text turn's mic hears nothing, so that 5 s timer is exactly the guard that ends
     * the conversation if the server never starts a turn (a StallTimeout would be a no-op in LISTENING). On the
     * normal path [Event.ModelActivity] cancels it the instant the model begins — well under 5 s. The prompt is
     * shown as the user's turn immediately so the chip text doesn't wait on the server.
     */
    private fun sendInitialText(text: String) {
        if (state.phase != Phase.LISTENING) return
        advanceTranscript(Transcript.Event.UserText(text))
        backend?.sendText(text)
        DebugLog.log("sent initial text: \"$text\"")
    }

    // ---- orchestration (the engine's thread) -------------------------------------------------------

    private fun dispatch(event: Event) {
        if (ended) return
        val decision = reduce(state, event, MULTI_TURN)
        // PlaybackBusy fires once per audio chunk (hundreds per answer) without changing phase — logging it
        // floods debug.txt and rolls out the lines that matter. Log every other transition; skip that one.
        val noisy = event == Event.PlaybackBusy && decision.state.phase == state.phase
        if (!noisy && (decision.state != state || decision.actions.isNotEmpty())) {
            DebugLog.log("event=${event::class.simpleName} → ${decision.state.phase}")
        }
        state = decision.state
        // Cancel any tool the reducer no longer waits on (cancelled or interrupted).
        toolRunner?.keepOnly(state.toolsInFlight)
        publishPhase(state.phase)
        decision.actions.forEach(::exec)
    }

    /** Mirror the reducer phase to the UI (ENDED is published as IDLE by [teardown]'s markIdle). */
    private fun publishPhase(phase: Phase) {
        sink.setPhase(
            when (phase) {
                Phase.CONNECTING -> ConversationHub.UiPhase.CONNECTING
                Phase.LISTENING -> ConversationHub.UiPhase.LISTENING
                Phase.SPEAKING -> ConversationHub.UiPhase.SPEAKING
                Phase.ENDED -> return
            },
        )
    }

    /** Advance the transcript by [event], publishing its turns only when they changed. */
    private fun advanceTranscript(event: Transcript.Event) {
        val next = transcript.on(event)
        val changed = next.turns !== transcript.turns
        transcript = next
        // After teardown, a message queued on the engine's thread just before stop() must not repopulate the hub — e.g.
        // the user tapped "New conversation" (clearHistory) the instant this turn ended.
        if (changed && !ended) sink.setTurns(next.turns)
    }

    private fun exec(action: Action) = when (action) {
        Action.StartForwarding -> {
            advanceTranscript(Transcript.Event.ListeningStarted(audio.playedBytes()))
            micGate?.listeningStarted()
        }

        Action.StopForwarding -> {
            micGate?.listeningStopped() // also ends connect buffering, should a model turn win the race
            sink.hideRecordingBar()
        }

        Action.ArmNoSpeechTimer -> {
            // The normal arm path (LISTENING entry + every server-confirmed UserSpeaking) — reset the grace
            // budget here so a confirmed transcript makes genuine speech effectively unbounded. The grace
            // re-arm in [noSpeechTimer] bypasses this action, so its count persists across unconfirmed checks.
            noSpeechGraces = 0
            clock.cancel(noSpeechTimer)
            clock.postDelayed(noSpeechTimer, NO_SPEECH_MS)
        }

        Action.CancelNoSpeechTimer -> clock.cancel(noSpeechTimer)

        Action.ArmStallTimer -> {
            clock.cancel(stallTimer)
            clock.postDelayed(stallTimer, stallMs)
        }

        Action.CancelStallTimer -> clock.cancel(stallTimer)

        Action.FlushPlayback -> audio.flushPlayback()

        Action.FireAfterSpeech -> toolRunner?.turnEnded()

        Action.End -> endNow()

        is Action.ExecuteTools -> toolRunner?.run(action.calls) { results ->
            dispatch(Event.ToolResultsReady(results))
        }

        is Action.SendToolResponse -> backend?.sendToolResponse(action.results)
    }

    private fun endNow() {
        if (ended) return
        ended = true
        DebugLog.log("conversation end → releasing mic")
        teardown()
        onEnded()
    }

    private fun teardown() {
        micGate?.listeningStopped()
        clock.cancel(noSpeechTimer)
        clock.cancel(stallTimer)
        toolRunner?.dispose() // interrupt running tools; drop deferred mute / DND-enable; deliver no more results
        runCatching { audio.closeMic() }
        runCatching { backend?.close() }
        backend = null
        audio.stopPlayer()
        sink.hideRecordingBar()
        sink.markIdle() // back to IDLE; the finished transcript stays on screen
    }

    companion object {
        // Phase 2.b: ongoing conversation — re-open the mic after each answer (the no-speech timer
        // ends it on silence). The mic stays open across turns (frames dropped while SPEAKING), so
        // portal-wake can't reclaim mid-conversation.
        private const val MULTI_TURN = true

        // The only two client timers (see ConversationState). Tunable on device.
        private const val NO_SPEECH_MS = 5_000L // release the mic if the user says nothing (device-tuned 2.b)
    }
}
