package com.portal.assistant.conversation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.portal.assistant.audio.MicCapture
import com.portal.assistant.audio.PcmPlayer
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.backend.CredentialMessages
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.tools.ToolRegistry
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocationProvider
import com.portal.assistant.system.NetworkStatus
import com.portal.assistant.ui.RecordingOverlay
import com.portal.assistant.ui.UiVisibility
import com.portal.commons.DebugLog
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * The impure shell around the pure [reduce] state machine: it owns the [VoiceBackend], [MicCapture],
 * [PcmPlayer], the recording bar, and the two timers, and runs the conversation as an ongoing
 * multi-turn exchange (Phase 2.b — [MULTI_TURN] = true): after each answer the mic re-opens for a
 * follow-up, and the conversation ends only when the user goes silent (the no-speech timer) or on
 * error. Every mic frame goes through the conversation's [MicGate]. (A "goodbye" fast-exit is deferred to a later
 * milestone.)
 *
 * **Single orchestration thread.** Everything funnels through [dispatch] on the main [Handler]: the
 * backend callbacks (e.g. OkHttp thread), the player's drain (writer thread), and the timer Runnables all
 * post an [Event] here. So state is touched on one thread only — no locks, no CAS. The only thing off
 * this thread is the hot audio path: mic frames go straight to the socket and audio chunks straight to
 * the player (both thread-safe), to keep latency low.
 *
 * Lifecycle: [start] connects + opens the mic; the conversation ends itself (the user goes silent or
 * an error) via the [Action.End] action, which tears everything down and calls [onEnded] so the
 * service can return to standby (mic released, so portal-wake reclaims by detection).
 */
class AssistantEngine(
    context: Context,
    private val backendChoice: Backends.Choice,
    private val onEnded: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val overlay = RecordingOverlay(appContext)
    private val player = PcmPlayer()

    private val stallMs: Long = backendChoice.factory.deadAirStallMs

    // Side effects deferred until the assistant finishes speaking the turn (mute, DND-enable). Fired by
    // Action.FireAfterSpeech on turn-end; cleared on teardown. Passed to the controllers via ToolRegistry.
    private val afterSpeech = AfterSpeech()
    private val toolRegistry = ToolRegistry(appContext, afterSpeech)

    // Tool execution: one thread so calls within a batch run sequentially (per-call timeout is the
    // tool's own I/O timeout or the Future.cancel on ToolCancelled). Per-id futures tracked for
    // targeted cancellation; map is also cleared on teardown.
    private val toolExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "tool-exec") }
    private val toolFutures = ConcurrentHashMap<String, Future<*>>()

    private var backend: VoiceBackend? = null
    private var mic: MicCapture? = null

    private var state = ConversationState()

    /** Chat transcript, mutated only on [handler] and mirrored to [ConversationHub] for the UI. */
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

    /** Wall-clock of the last audio chunk (set on the OkHttp thread); used to diagnose stalls. */
    @Volatile private var lastAudioAtMs = 0L

    /** Wall-clock of the last LISTENING frame whose gained level cleared the VAD threshold (capture thread). */
    @Volatile private var lastSpeechAtMs = 0L

    /** Consecutive server-unconfirmed no-speech graces granted this listening window (handler-thread only). */
    private var noSpeechGraces = 0

    /** Paced word-reveal bookkeeping for the current model turn (handler-thread only). */
    private val reveal = RevealTracker { player.playedBytes() }

    private val noSpeechTimer: Runnable = Runnable {
        // Bounded local-energy grace: if the mic still hears the user at the deadline, defer the end so a
        // follow-up started late in the window survives until the server's transcript arrives (which re-arms
        // via the normal path and resets the budget). The cap bounds noise that never transcribes — see
        // [NoSpeechGrace] and the note in ConversationState's NoSpeechTimeout handling.
        val now = System.currentTimeMillis()
        if (NoSpeechGrace.shouldExtend(now, lastSpeechAtMs, noSpeechGraces)) {
            noSpeechGraces++
            DebugLog.log(
                "no-speech grace $noSpeechGraces/${NoSpeechGrace.MAX_GRACES} " +
                    "(local speech ${now - lastSpeechAtMs}ms ago) — +${NoSpeechGrace.GRACE_MS}ms for server confirm",
            )
            handler.postDelayed(noSpeechTimer, NoSpeechGrace.GRACE_MS) // re-arm WITHOUT resetting the count
        } else {
            dispatch(Event.NoSpeechTimeout)
        }
    }

    private val stallTimer = Runnable {
        // Dead-air watchdog: the reducer arms this only via stallDecision (idle + no tools, plus the
        // LISTENING→SPEAKING entry arms) — so reaching here is a genuine stall; no defer needed.
        // Diagnose: thinking gap before any audio, or audio that started then stopped?
        val gap = if (lastAudioAtMs == 0L) -1 else System.currentTimeMillis() - lastAudioAtMs
        val modelChars = transcript.turns.lastOrNull()?.text?.length ?: 0
        DebugLog.log("stall fired: ${gap}ms since last audio, turnComplete=${state.turnComplete}, modelChars=$modelChars")
        dispatch(Event.StallTimeout)
    }

    /**
     * @param resume continue the on-screen conversation (replay its recent turns as context). True only for a
     * foreground **mic-tap** with a transcript present; a wake trigger and a fresh tap pass false → clean start
     * (any stale transcript is cleared by [ConversationHub.startFresh]). Multi-turn *within* this conversation
     * is the live backend session, not replayed context.
     * @return false when the conversation could not open (missing credential). The service gates before
     * construction; this path is defense-in-depth only — it posts a notice and bails without calling [onEnded].
     */
    fun start(resume: Boolean, initialText: String? = null): Boolean {
        // Sent as the first user turn once the socket is ready (a tapped suggestion); see onReady.
        pendingInitialText = initialText?.takeIf { it.isNotBlank() }
        // No credential → don't open a socket that can only fail. Post the notice and bail BEFORE publishing
        // CONNECTING, so the user gets a plain message with no connecting flash (the phase stays IDLE, so
        // the banner sits over the idle home).
        if (backendChoice.credentialMissing) {
            DebugLog.log("no credential → cannot start (kind=${backendChoice.kind})")
            ConversationHub.postNotice(CredentialMessages.missing(backendChoice.kind))
            ended = true
            return false
        }
        // Retry a failed/stale IP-geo lookup (prewarm is one-shot); guarded internally, a no-op when fresh.
        // Result lands for the NEXT conversation — this one uses whatever's already cached.
        LocationProvider.refreshIfStale(appContext)
        val prior = ConversationHub.session.value.turns
        val externalToolLines = toolRegistry.externalPromptLines()
        val deviceContextLines = deviceContextLines()
        val resumeTurns: List<Turn>
        if (resume && prior.isNotEmpty()) {
            transcript = Transcript(turns = prior)
            resumeTurns = ResumeContext.recentContext(prior, RESUME_MAX_CHARS) // may be < prior on long chats
            DebugLog.log("engine start (resume, ${prior.size} turns, ${resumeTurns.size} replayed)")
            ConversationHub.startResume()
        } else {
            DebugLog.log("engine start")
            transcript = Transcript()
            resumeTurns = emptyList()
            ConversationHub.startFresh()
        }
        val systemPrompt = SessionPrompt.build(
            externalToolLines = externalToolLines,
            deviceContextLines = deviceContextLines,
            resumeTurns = resumeTurns,
        )
        // Lead with the two real-latency operations — the WebSocket handshake (the connect long pole) and the
        // AudioRecord open — so the synchronous player setup below (~27 ms of AudioTrack alloc + writer thread)
        // overlaps the network round-trip instead of delaying it. Playback isn't needed until the model speaks
        // (long after Ready), and nothing reads `player` before it's started — onAudio can't fire pre-Ready,
        // onFrame never touches it — so starting it after connect()/mic is safe.
        backend = backendChoice.factory.create(
            BackendSessionConfig.build(
                choice = backendChoice,
                model = AppPrefs.modelId(appContext),
                speaker = AppPrefs.speakerId(appContext), // Chosen speaker (null = Server default); Local ignores it
                systemPrompt = systemPrompt,
                functionDeclarations = toolRegistry.declarations(),
            ),
            backendListener,
        ).also { it.connect() }
        // pendingInitialText (set above) is non-null only for a chip, whose query is text, not speech.
        val voiceQuery = pendingInitialText == null
        val playsListeningTone = listeningTonePlaysAtMicOpen(voiceQuery)
        // Built before mic.start() spawns the capture thread, so its initial state is visible to it. With the tone
        // on, the gate starts with it pending: from the first frame nothing is sent until the tone has started.
        val gate = MicGate(voiceQuery, playsListeningTone).also { micGate = it }
        mic = MicCapture { buf, n -> onFrame(gate, buf, n) }.also { it.start() }
        if (playsListeningTone) playListeningTone(gate)
        player.start()
        player.setOnDrained { handler.post { dispatch(Event.PlaybackIdle) } }
        player.setOnLevel { lvl ->
            ConversationHub.setAudioLevel(lvl) // drives the speaking visualizer
            if (reveal.recompute(latestModelText())) publishTurns() // pace reveal to audio actually played
        }
        return true
    }

    /**
     * Whether this conversation's mic open plays the Listening tone. Called only from [start], after the credential
     * check, so a conversation that can't start never plays it, and a follow-up re-listen ([Action.StartForwarding])
     * never reaches it. The setting is read per conversation, so a change applies from the next one.
     */
    private fun listeningTonePlaysAtMicOpen(voiceQuery: Boolean): Boolean {
        val toneEnabled = AppPrefs.listeningToneEnabled(appContext)
        val playsTone = ListeningTone.playsAtMicOpen(toneEnabled, voiceQuery, conversationStart = true)
        if (!playsTone) DebugLog.log("listening tone: skipped (enabled=$toneEnabled, voiceQuery=$voiceQuery)")
        return playsTone
    }

    /**
     * Play the Listening tone at the mic open, with the [gate] already holding it pending. Async: the mic and connect
     * are already under way and don't wait on it. Its start hands the gate the suppression window; a failure to play
     * ends the wait, so the conversation is sent as if the tone were off.
     */
    private fun playListeningTone(gate: MicGate) {
        val micOpenedAtMs = SystemClock.elapsedRealtime()
        ListeningTonePlayer.play(
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

    /**
     * Device context injected into the system prompt at session start: local clock/zone (snapshot; the
     * portal.get_time tool provides the live value for time queries) and approximate location (user
     * override → IP-geo with lat/lon → LOCATION_UNKNOWN). systemInstruction is frozen at session setup,
     * so the clock snapshot advances only between sessions.
     */
    private fun deviceContextLines(): List<String> {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
            .format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()))
        val override = LocationProvider.override(appContext)
        val location = when {
            override != null -> SystemContext.overrideLine(override)

            else -> LocationProvider.current(appContext)
                ?.let { SystemContext.locationLine(it) } ?: SystemContext.LOCATION_UNKNOWN
        }
        return listOf(SystemContext.timeLine(now, zone.id), location)
    }

    /** Tear down without firing [onEnded] (the service is already stopping). Idempotent. */
    fun stop() {
        if (ended) return
        ended = true
        teardown()
    }

    // ---- hot audio path (off the orchestration thread) ---------------------------------------------

    /** Capture thread. The [MicGate] decides what this frame sends, shows and logs; this only applies its outcome. */
    private fun onFrame(gate: MicGate, buf: ByteArray, n: Int) {
        val outcome = gate.onFrame(buf, n, SystemClock.elapsedRealtime())
        outcome.logLines.forEach(DebugLog::log)
        outcome.audioToSend.forEach { audio -> backend?.sendAudio(audio) }
        // Local speech signal for the no-speech grace, on the same wall clock its timer reads.
        if (outcome.speechHeard) lastSpeechAtMs = System.currentTimeMillis()
        // The orange bar is the *backgrounded* mic indicator; when the app is open the in-UI state replaces it.
        // (bar add + "overlay shown" log live in RecordingOverlay.show())
        when (val indicator = outcome.indicator) {
            MicGate.Indicator.None -> Unit
            MicGate.Indicator.BarAtRest -> overlay.setRecording(!UiVisibility.inForeground)
            is MicGate.Indicator.Level -> {
                ConversationHub.setAudioLevel(indicator.level) // drives the in-app listening visualizer
                overlay.setRecording(!UiVisibility.inForeground, indicator.level)
            }
        }
    }

    private val backendListener = object : VoiceBackend.Listener {
        override fun onReady() {
            handler.post {
                connectedOk = true
                dispatch(Event.Ready) // CONNECTING → LISTENING (mic armed)
                // If a suggestion was tapped, send it now as the first turn (we're LISTENING + setupDone).
                pendingInitialText?.let { text ->
                    pendingInitialText = null
                    sendInitialText(text)
                }
            }
        }
        override fun onInputTranscript(textDelta: String) {
            handler.post {
                transcript = transcript.appendUser(textDelta)
                publishTurns()
                dispatch(Event.UserSpeaking)
            }
        }
        override fun onOutputTranscript(textDelta: String) {
            handler.post {
                // SPEAKING only — see [acceptOutputTranscript] (no belated LISTENING append / mute).
                if (!acceptOutputTranscript(state.phase)) return@post
                transcript = transcript.appendModel(textDelta)
                publishTurns()
                dispatch(Event.ModelActivity)
            }
        }
        override fun onAudio(pcm24k: ByteArray) {
            lastAudioAtMs = System.currentTimeMillis()
            player.enqueue(pcm24k) // enqueue synchronously (low latency)
            // Account for the received bytes on the handler — NOT here on the OkHttp thread — so it lands
            // after startModelTurn()'s reset (also handler-posted, from the same message's ModelGenerating).
            // Otherwise a turn whose first audio shares the opening message gets those bytes wiped by the
            // late reset, skewing the paced-reveal fraction and lagging the opening words.
            val size = pcm24k.size
            handler.post {
                reveal.onAudioReceived(size) // denominator for the paced-reveal fraction
                if (reveal.recompute(latestModelText())) publishTurns()
                dispatch(Event.PlaybackBusy)
            }
        }
        override fun onTurnComplete() = post(Event.TurnComplete)
        override fun onInterrupted() {
            handler.post {
                // A re-spoken turn mid-SPEAKING gets a fresh bubble explicitly (no StartForwarding precedes it).
                transcript = transcript.beginModelTurn()
                reveal.reset()
                dispatch(Event.Interrupted)
            }
        }
        override fun onModelGenerating() {
            handler.post {
                // The model bubble is armed at the turn boundary (StartForwarding), so DON'T re-arm here —
                // re-arming mid-turn would split the answer (and dropped its opening words when transcription
                // arrived before the first modelTurn). Just reset the reveal counters once, on the first one.
                if (state.phase != Phase.SPEAKING) reveal.reset()
                dispatch(Event.ModelActivity)
            }
        }
        override fun onToolCall(calls: List<FunctionCall>) {
            handler.post { dispatch(Event.ToolCallReceived(calls)) }
        }
        override fun onToolCancel(ids: List<String>) {
            handler.post { dispatch(Event.ToolCancelled(ids)) }
        }
        override fun onServerClosingSoon(graceMs: Long) {
            DebugLog.log("backend closing soon (grace=${graceMs}ms)") // handled in 2.c; let onClosed end it
        }
        override fun onError(message: String) {
            DebugLog.log("backend error: $message")
            handler.post { surfaceDisconnect(notice = message) }
        }
        override fun onClosed() {
            handler.post { surfaceDisconnect() }
        }
    }

    /**
     * An error or close from the socket. A *normal* end (user went silent) sets [ended] before it closes the
     * socket, so reaching here with `ended == false` means the connection dropped on us unexpectedly — show a
     * banner before letting [Event.Disconnected] tear the conversation down. (When `ended` is already true this
     * is just our own teardown closing the socket; [dispatch] ignores the event and we post nothing.)
     *
     * Double-fire safe: a failed socket emits onError THEN onClosed. The first posts the notice and ends the
     * turn (so `ended` flips true); the second sees `ended == true` → posts nothing and its dispatch no-ops.
     * (Even a redundant post would be idempotent — [ConversationHub.notice] is a StateFlow that drops an
     * equal value.)
     */
    private fun surfaceDisconnect(notice: String? = null) {
        if (!ended) {
            ConversationHub.postNotice(userDisconnectNotice(notice))
        }
        dispatch(Event.Disconnected)
    }

    private fun userDisconnectNotice(backendError: String?): String = DisconnectNotice.message(
        backendError = backendError,
        offline = !NetworkStatus.isOnline(appContext),
        connectedOk = connectedOk,
    )

    /**
     * Send a tapped suggestion as the user's first turn. Runs on the handler right after [Event.Ready] put us
     * in LISTENING (so [VoiceBackend.sendText] sees the session ready). We deliberately KEEP the no-speech timer that
     * LISTENING just armed: a text turn's mic hears nothing, so that 5 s timer is exactly the guard that ends
     * the conversation if the server never starts a turn (a StallTimeout would be a no-op in LISTENING). On the
     * normal path [Event.ModelActivity] cancels it the instant the model begins — well under 5 s. The prompt is
     * shown as the user's turn immediately so the chip text doesn't wait on the server.
     */
    private fun sendInitialText(text: String) {
        if (state.phase != Phase.LISTENING) return
        transcript = transcript.appendUser(text)
        publishTurns()
        backend?.sendText(text)
        DebugLog.log("sent initial text: \"$text\"")
    }

    // ---- orchestration (main thread) ---------------------------------------------------------------

    private fun post(event: Event) {
        handler.post { dispatch(event) }
    }

    private fun dispatch(event: Event) {
        if (ended) return
        val oldInFlight = state.toolsInFlight
        val decision = reduce(state, event, MULTI_TURN)
        // PlaybackBusy fires once per audio chunk (hundreds per answer) without changing phase — logging it
        // floods debug.txt and rolls out the lines that matter. Log every other transition; skip that one.
        val noisy = event == Event.PlaybackBusy && decision.state.phase == state.phase
        if (!noisy && (decision.state != state || decision.actions.isNotEmpty())) {
            DebugLog.log("event=${event::class.simpleName} → ${decision.state.phase}")
        }
        state = decision.state
        // Cancel futures for any IDs the reducer just removed from toolsInFlight (cancelled or interrupted).
        val removedIds = oldInFlight - decision.state.toolsInFlight
        removedIds.forEach { id -> toolFutures.remove(id)?.cancel(true) }
        publishPhase(state.phase)
        decision.actions.forEach(::exec)
    }

    /** Mirror the reducer phase to the UI (ENDED is published as IDLE by [teardown]'s markIdle). */
    private fun publishPhase(phase: Phase) {
        ConversationHub.setPhase(
            when (phase) {
                Phase.CONNECTING -> ConversationHub.UiPhase.CONNECTING
                Phase.LISTENING -> ConversationHub.UiPhase.LISTENING
                Phase.SPEAKING -> ConversationHub.UiPhase.SPEAKING
                Phase.ENDED -> return
            },
        )
    }

    /** The latest turn's text iff it's the model's — the reveal target. */
    private fun latestModelText(): String? = transcript.turns.lastOrNull()?.takeIf { it.role == Role.MODEL }?.text

    private fun publishTurns() {
        // After teardown, a handler message queued just before stop() must not repopulate the hub — e.g. the
        // user tapped "New conversation" (clearHistory) the instant this turn ended.
        if (ended) return
        ConversationHub.setTurns(turnsWithReveal())
    }

    /** The transcript turns with the current reveal count stamped on the latest turn if it's the model's. */
    private fun turnsWithReveal(): List<Turn> {
        val turns = transcript.turns
        val last = turns.lastOrNull() ?: return turns
        if (last.role != Role.MODEL) return turns
        return turns.dropLast(1) + last.copy(revealedWords = reveal.revealedWords)
    }

    private fun exec(action: Action) = when (action) {
        Action.StartForwarding -> {
            // Arm BOTH a fresh user turn and a fresh model turn at the boundary. Arming the model turn here
            // (not in onModelGenerating) makes the bubble order-independent: the next model delta — text or
            // audio, whichever the server sends first — opens a fresh bubble instead of extending the prior
            // answer's, which dropped the new answer's opening words when transcription arrived first.
            transcript = transcript.beginUserTurn().beginModelTurn()
            micGate?.listeningStarted()
        }

        Action.StopForwarding -> {
            micGate?.listeningStopped() // also ends connect buffering, should a model turn win the race
            overlay.setRecording(false)
        }

        Action.ArmNoSpeechTimer -> {
            // The normal arm path (LISTENING entry + every server-confirmed UserSpeaking) — reset the grace
            // budget here so a confirmed transcript makes genuine speech effectively unbounded. The grace
            // re-arm in [noSpeechTimer] bypasses this action, so its count persists across unconfirmed checks.
            noSpeechGraces = 0
            handler.removeCallbacks(noSpeechTimer)
            handler.postDelayed(noSpeechTimer, NO_SPEECH_MS)
        }

        Action.CancelNoSpeechTimer -> handler.removeCallbacks(noSpeechTimer)

        Action.ArmStallTimer -> {
            handler.removeCallbacks(stallTimer)
            handler.postDelayed(stallTimer, stallMs)
        }

        Action.CancelStallTimer -> handler.removeCallbacks(stallTimer)

        Action.FlushPlayback -> player.flush()

        Action.FireAfterSpeech -> afterSpeech.fire()

        Action.End -> endNow()

        is Action.ExecuteTools -> submitToolBatch(action.calls)

        is Action.SendToolResponse -> backend?.sendToolResponse(action.results)
    }

    /**
     * Submit one batch of tool calls to the executor. Calls run sequentially (single-thread executor);
     * the last to finish posts one [Event.ToolResultsReady] back to the handler. Per-id futures are
     * tracked in [toolFutures] so [dispatch] can cancel them via [Future.cancel] when
     * `toolsInFlight` shrinks (ToolCancelled, Interrupted).
     *
     * Limitation (known): if a call is cancelled *before* it starts (queued but not running), the
     * batch counter never reaches zero and [Event.ToolResultsReady] is not posted for the remaining
     * calls. This is moot for single-call batches (Steps 4–5). Revisit when multi-call batches appear.
     */
    private fun submitToolBatch(calls: List<FunctionCall>) {
        val batch = calls.toList()
        val pending = AtomicInteger(batch.size)
        val results = arrayOfNulls<ToolResult>(batch.size)

        batch.forEachIndexed { i, call ->
            val f = toolExecutor.submit {
                results[i] = runCatching {
                    ToolResult(call.id, call.name, toolRegistry.invoke(call.name, call.args))
                }.getOrElse { e ->
                    ToolResult(call.id, call.name, JSONObject().put("error", e.message ?: "error"))
                }
                toolFutures.remove(call.id)
                if (pending.decrementAndGet() == 0) {
                    val ordered = List(batch.size) { j ->
                        results[j] ?: ToolResult(batch[j].id, batch[j].name, JSONObject().put("error", "cancelled"))
                    }
                    handler.post { if (!ended) dispatch(Event.ToolResultsReady(ordered)) }
                }
            }
            toolFutures[call.id] = f
        }
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
        handler.removeCallbacks(noSpeechTimer)
        handler.removeCallbacks(stallTimer)
        toolExecutor.shutdownNow() // interrupt any in-flight tool tasks
        toolFutures.clear()
        afterSpeech.clear() // drop any deferred mute / DND-enable so it can't fire into the next conversation
        toolRegistry.dispose() // and clear the controllers' pending-effect handles
        runCatching { mic?.stop() }
        mic = null
        runCatching { backend?.close() }
        backend = null
        player.stop()
        overlay.dismiss()
        ConversationHub.markIdle() // back to IDLE; the finished transcript stays on screen
    }

    private companion object {
        // Phase 2.b: ongoing conversation — re-open the mic after each answer (the no-speech timer
        // ends it on silence). The mic stays open across turns (frames dropped while SPEAKING), so
        // portal-wake can't reclaim mid-conversation.
        const val MULTI_TURN = true

        // When continuing (foreground mic resume), the size budget (chars ≈ token proxy) of recent history
        // replayed to the model as context — bounds the prompt as a continued chat grows. Device-tunable.
        const val RESUME_MAX_CHARS = 4_000

        // The only two client timers (see ConversationState). Tunable on device.
        const val NO_SPEECH_MS = 5_000L // release the mic if the user says nothing (device-tuned 2.b)
    }
}
