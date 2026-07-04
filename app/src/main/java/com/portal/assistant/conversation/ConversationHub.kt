package com.portal.assistant.conversation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Gen2 wake-model runtime download state for the idle-home setup indicator (see [ConversationHub.modelSetup]). */
sealed interface ModelSetup {
    /** Nothing to show: model installed/downloaded, or gen1 (no in-app detector). */
    data object Idle : ModelSetup

    /** The one-time model download is in flight; [progress] is 0f–1f. */
    data class Downloading(val progress: Float) : ModelSetup

    /** The download failed (offline/interrupted); hands-free wake stays off until a later attempt succeeds. */
    data object Failed : ModelSetup
}

/**
 * Process-wide bridge between the headless conversation (which lives in [AssistantEngine], hosted by the
 * resident [com.portal.assistant.service.AssistantService]) and the foreground chat UI ([MainActivity]).
 *
 * Both run in the same process, and a conversation may already be in flight (started by "hey jarvis") when
 * the user opens the app — so rather than bind the started service, the engine *publishes* live state here
 * and the UI *observes* it (read-only).
 *
 * Two kinds of state:
 * - [session] — the semantic [ConversationSession] (phase, turns). Written by the engine on its single
 *   orchestration (main-looper) thread, and via [clearHistory] by the hosting service ("New conversation")
 *   and [MainActivity] (reopen when idle). All on the main thread.
 * - [audioLevel] — a high-frequency UI signal, written from the audio **capture** thread
 *   ([AssistantEngine.onFrame]) and **playback** thread (the player's level callback). Kept off [session]
 *   so a per-chunk update doesn't rebuild the whole session value. (The paced-reveal word count rides on
 *   the latest model [Turn] inside [session] instead — see [RevealProgress].)
 *
 * All reads go through thread-safe [StateFlow]. (Activity-foreground state lives separately in
 * [com.portal.assistant.ui.UiVisibility], not on this bus.)
 *
 * Lifecycle: [startFresh] (or [startResume], which keeps the turns) begins a conversation; when one ends the phase returns to
 * [UiPhase.IDLE] but the turns stay on screen until the next start. No conversation ever run → empty turns →
 * the greeting screen.
 */
object ConversationHub {

    /** UI-facing phase. [IDLE] = no live conversation (greeting, or a finished transcript on screen). */
    enum class UiPhase { IDLE, CONNECTING, LISTENING, SPEAKING }

    private val nextId = AtomicLong(0)

    private val _session = MutableStateFlow(ConversationSession(id = nextId.get()))
    val session: StateFlow<ConversationSession> = _session.asStateFlow()

    /** 0..1 — mic level while LISTENING, playback level while SPEAKING. Drives the audio visualizer. */
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /**
     * A transient, user-facing message shown as a banner: a connection/offline failure, a missing API key,
     * or a denied mic permission. Survives the conversation ending (a failure ends it, then we want the
     * banner to stay) — cleared only by a new attempt ([startFresh]/[startResume]/[clearHistory]), an
     * explicit [clearNotice], or the UI's auto-dismiss. Posted by the engine and by [MainActivity].
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Gen2 wake-model runtime download state for the idle-home setup indicator — the model is fetched on first
     * gen2 use, not shipped in the APK (see [com.portal.assistant.system.WakeModelInstaller]). [ModelSetup.Idle]
     * when there's nothing to show (installed, or gen1). Written by the hosting service; observed by the UI.
     */
    private val _modelSetup = MutableStateFlow<ModelSetup>(ModelSetup.Idle)
    val modelSetup: StateFlow<ModelSetup> = _modelSetup.asStateFlow()

    fun setModelSetup(state: ModelSetup) {
        _modelSetup.value = state
    }

    // ---- writers (engine on its Handler thread; clearHistory also from the service + MainActivity) ------

    /** Begin a fresh conversation: new id, CONNECTING, no prior turns. */
    internal fun startFresh() {
        _notice.value = null // a new attempt clears any stale failure banner
        _session.value = ConversationSession(id = nextId.incrementAndGet(), phase = UiPhase.CONNECTING)
        zeroSignals()
    }

    /** Continue the on-screen conversation (foreground mic): new id, CONNECTING, **keep** the turns. */
    internal fun startResume() {
        _notice.value = null
        _session.update { it.copy(id = nextId.incrementAndGet(), phase = UiPhase.CONNECTING) }
        zeroSignals()
    }

    /**
     * Drop the visible transcript back to the greeting (new empty IDLE session). Used by "New conversation"
     * ("+") and by [MainActivity] on reopen when no conversation is live (a finished transcript shouldn't
     * reappear).
     */
    internal fun clearHistory() {
        _notice.value = null
        _session.value = ConversationSession(id = nextId.incrementAndGet(), phase = UiPhase.IDLE)
        zeroSignals()
    }

    /** Show a failure/permission banner. Thread-safe (MutableStateFlow); callers post from any thread. */
    fun postNotice(message: String) {
        _notice.value = message
    }

    /** Dismiss the banner (auto-dismiss timer or user tap). */
    fun clearNotice() {
        _notice.value = null
    }

    internal fun setPhase(phase: UiPhase) {
        _session.update { it.copy(phase = phase) }
    }

    internal fun setTurns(turns: List<Turn>) {
        _session.update { it.copy(turns = turns) }
    }

    internal fun setAudioLevel(level: Float) {
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    /** Conversation ended: drop to IDLE (and zero the signals) but leave the transcript visible. */
    internal fun markIdle() {
        _session.update { it.copy(phase = UiPhase.IDLE) }
        zeroSignals()
    }

    private fun zeroSignals() {
        _audioLevel.value = 0f
    }
}
