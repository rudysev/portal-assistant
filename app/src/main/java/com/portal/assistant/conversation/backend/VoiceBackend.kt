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
 * PCM** up via [sendAudio] and expects **24 kHz mono 16-bit PCM** back as [BackendEvent.Audio]. A
 * backend whose model uses other rates must resample on its own side.
 *
 * **First audio (invariant):** [BackendEvent.ModelGenerating] arrives before the first [BackendEvent.Audio] of
 * every turn. The engine relies on it — without it, Jarvis's answer plays while the mic keeps sending and the
 * orange bar stays up. [WebSocketVoiceBackend] guarantees it for every backend on it; any other backend must too.
 *
 * **Threading:** events may arrive on any thread (e.g. a socket's reader thread); the engine
 * marshals them onto its single orchestration thread, so implementations need not post — just invoke.
 */
interface VoiceBackend {

    /** Open the session (connect the transport, send setup). Emits [BackendEvent.Ready] when usable. */
    fun connect()

    /** Stream one chunk of 16 kHz mono 16-bit PCM mic audio to the model. */
    fun sendAudio(pcm: ByteArray)

    /** Send one complete text turn as the user (e.g. a tapped suggestion); the model answers immediately. */
    fun sendText(text: String)

    /** Send tool results back to the model. [results] order must match the original [BackendEvent.ToolCall] order. */
    fun sendToolResponse(results: List<ToolResult>)

    /** Close the session and release the transport. Idempotent. */
    fun close()

    /** The one callback the backend raises as the session progresses. Neutral across models. */
    fun interface Listener {
        /** The next [BackendEvent] of this session, in order — what the server said, then how the socket ended. */
        fun onEvent(event: BackendEvent)
    }
}

/**
 * Model-neutral inputs for one session, passed to every [VoiceBackendFactory]. Fields are not uniform
 * across backends — each factory reads the slice it needs and ignores the rest:
 *  - **Gemini:** [credential] = API key, [model] required, [speaker] = the Chosen speaker's name (null =
 *    Server default), [gemini] wire options, [local] ignored.
 *  - **Local:** [credential] = canonical `wss://` host URL, [model] and [speaker] ignored, [local] wire
 *    options, [gemini] ignored.
 *
 * Shared: [systemPrompt], [functionDeclarations]. [kind] tags which factory was selected so adapters
 * can assert or branch without guessing from [credential] shape.
 */
data class BackendConfig(
    val credential: String?,
    val model: String,
    val speaker: String? = null,
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
