package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.system.AppPrefs
import org.json.JSONObject

/**
 * The model-neutral seam between the engine and whatever voice model powers the conversation. The
 * engine depends only on this interface and [VoiceBackendFactory]; the concrete backend is chosen in
 * [Backends]. Everything downstream — the pure reducer, the tool registry, the audio pipeline, the UI —
 * is backend-agnostic.
 *
 * **Audio contract (invariant, not encoded in signatures):** the engine streams **16 kHz mono 16-bit
 * PCM** up via [sendAudio] and expects **24 kHz mono 16-bit PCM** back via [Listener.onAudio]. A
 * backend whose model uses other rates must resample on its own side.
 *
 * **Threading:** callbacks may arrive on any thread (e.g. a socket's reader thread); the engine
 * marshals them onto its single orchestration thread, so implementations need not post — just invoke.
 */
interface VoiceBackend {

    /** Open the session (connect the transport, send setup). Emits [Listener.onReady] when usable. */
    fun connect()

    /** Stream one chunk of 16 kHz mono 16-bit PCM mic audio to the model. */
    fun sendAudio(pcm: ByteArray)

    /** Send one complete text turn as the user (e.g. a tapped suggestion); the model answers immediately. */
    fun sendText(text: String)

    /** Send tool results back to the model. [results] order must match the original [Listener.onToolCall] order. */
    fun sendToolResponse(results: List<ToolResult>)

    /** Close the session and release the transport. Idempotent. */
    fun close()

    /** Callbacks the backend raises as the session progresses. Neutral across models. */
    interface Listener {
        /** The session is set up and ready — the engine moves CONNECTING → LISTENING. */
        fun onReady()

        /** A delta of the user's transcribed speech. */
        fun onInputTranscript(textDelta: String)

        /** The model's own words, transcribed as it speaks — drives the word-by-word chat bubble. */
        fun onOutputTranscript(textDelta: String)

        /** A chunk of 24 kHz mono 16-bit PCM speech from the model. */
        fun onAudio(pcm24k: ByteArray)

        fun onTurnComplete()

        /** The model's in-progress turn was interrupted (barge-in / server cut). */
        fun onInterrupted()

        /**
         * The model has started this turn — fired on the first content, including a text-only "thinking"
         * part before any audio (e.g. while it runs a search). Signals the user's turn is over and we're
         * now waiting on the model.
         */
        fun onModelGenerating()

        /** The model wants to call one or more on-device tools. Engine must respond with [sendToolResponse]. */
        fun onToolCall(calls: List<FunctionCall>)

        /** The backend cancelled the listed tool calls (ids); engine should abandon them. */
        fun onToolCancel(ids: List<String>)

        /**
         * The backend will terminate this session soon; [graceMs] is a best-effort remaining budget.
         * Backends without a bounded session lifetime (e.g. a local server) simply never fire this.
         */
        fun onServerClosingSoon(graceMs: Long)

        /** A transport/session error. The engine surfaces a user-facing notice and ends the turn. */
        fun onError(message: String)

        fun onClosed()
    }
}

/**
 * Model-neutral inputs for one session, passed to every [VoiceBackendFactory]. Fields are not uniform
 * across backends — each factory reads the slice it needs and ignores the rest:
 *  - **Gemini:** [credential] = API key, [model] required, [gemini] wire options, [local] ignored.
 *  - **Local:** [credential] = canonical `wss://` host URL, [model] ignored, [local] wire options,
 *    [gemini] ignored.
 *
 * Shared: [systemPrompt], [functionDeclarations]. [kind] tags which factory was selected so adapters
 * can assert or branch without guessing from [credential] shape.
 */
data class BackendConfig(
    val credential: String?,
    val model: String,
    val systemPrompt: String,
    val functionDeclarations: List<JSONObject> = emptyList(),
    val kind: AppPrefs.VoiceBackendKind = AppPrefs.VoiceBackendKind.GEMINI,
    val gemini: GeminiWireOptions = GeminiWireOptions(),
    val local: LocalWireOptions = LocalWireOptions(),
)

/** Builds a [VoiceBackend] for a session. Swapping the model = swapping the factory in [Backends]. */
interface VoiceBackendFactory {

    /**
     * How long [AssistantEngine] waits for the first audio (or post-drain dead air) before giving up on a
     * wedged model turn. Each backend's pipeline differs — Gemini co-streams audio fast; a LAN host may sit
     * silent through STT → LLM → on-device tool round-trips → optional web search → local TTS. Hardware
     * varies on LAN, so the local budget is a conservative generic default, not tuned to one machine.
     */
    val deadAirStallMs: Long

    fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend
}
