package com.portal.assistant.conversation.backend.local

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory
import com.portal.assistant.system.LocalVoiceHost
import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for a **local voice host** on the LAN, over our own minimal, vendor-
 * agnostic WebSocket protocol. Structurally a sibling of
 * [LiveClient][com.portal.assistant.gemini.LiveClient] — just the WebSocket + JSON/PCM boundary; the
 * conversation logic lives in the [com.portal.assistant.conversation] reducer/engine, which stays
 * backend-agnostic (it still streams 16 kHz PCM up and gets 24 kHz PCM + transcripts back).
 *
 * The host does everything Gemini's server did for free — VAD, ASR, the LLM (tool-calling), TTS, and
 * web-search grounding — so the app just ships audio and control frames. The protocol is deliberately
 * modeled on neither Gemini's nor OpenAI's wire format:
 *  - **binary frame** = one raw PCM chunk (16 kHz up / 24 kHz down) — no base64, it's a LAN socket.
 *  - **text frame (JSON)** = control, keyed by `type` (see [parseServerMessage] / the builders below).
 *    `input_transcript` and `output_transcript` carry **deltas** (new text since the last frame for that
 *    side), not the full utterance — the engine accumulates them into the chat bubble, same as Gemini Live.
 *
 * Auth: none — the "credential" is just the host URL (canonical `wss://` from [LocalVoiceHost]). TLS is
 * required; the host auto-generates a self-signed cert and [Http.lanVoice] connects with encryption but
 * without authenticating the server (trust-all). Callbacks arrive on OkHttp's thread; the engine marshals
 * them onto its single orchestration thread. Inbound JSON parsing is the pure, unit-tested
 * [parseServerMessage]; the socket callbacks feed it and relay [ServerEvent]s to [listener]. The host should
 * emit `model_generating` before the first audio frame; the client also infers it on first binary PCM as a
 * safety net.
 */
class LocalBackend(
    private val hostUrl: String,
    private val systemPrompt: String,
    private val functionDeclarations: List<JSONObject> = emptyList(),
    private val listener: VoiceBackend.Listener,
) : VoiceBackend {

    private val http = Http.lanVoice.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep the socket open
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null

    @Volatile private var setupDone = false

    /**
     * Per-turn latch for the first-audio `model_generating` safety net. Cleared on [ServerEvent.TurnComplete]
     * so multi-turn sessions on one WebSocket get a fresh net each turn.
     */
    @Volatile private var modelGeneratingEmitted = false

    override fun connect() {
        if (hostUrl.isBlank()) {
            listener.onError("Local server address is not set")
            return
        }
        val wssUrl = parseWssUrl(hostUrl)
        if (wssUrl == null) {
            listener.onError("Local server address is invalid: $hostUrl")
            return
        }
        val request = runCatching { Request.Builder().url(wssUrl).build() }.getOrNull()
        if (request == null) {
            listener.onError("Local server address is invalid: $hostUrl")
            return
        }
        DebugLog.log("local connecting host=$wssUrl")
        ws = http.newWebSocket(request, socketListener)
    }

    /** Stream one chunk of 16 kHz mono 16-bit PCM mic audio as a **binary** frame (the [VoiceBackend] contract). */
    override fun sendAudio(pcm: ByteArray) {
        val sock = ws ?: return
        if (!setupDone) return
        sock.send(pcm.toByteString())
    }

    /** Send one complete text turn as the user (e.g. a tapped suggestion); the host answers immediately. */
    override fun sendText(text: String) {
        val sock = ws
        if (sock == null || !setupDone) {
            DebugLog.log("local sendText dropped (ws=${sock != null}, setupDone=$setupDone)")
            return
        }
        sock.send(buildUserText(text))
    }

    /** Send tool results back to the host. [results] order must match the original [ServerEvent.ToolCall] order. */
    override fun sendToolResponse(results: List<ToolResult>) {
        val sock = ws ?: return
        if (!setupDone) return
        sock.send(buildToolResult(results))
    }

    override fun close() {
        runCatching { ws?.close(1000, "client closing") }
        ws = null
        setupDone = false
        modelGeneratingEmitted = false
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setup = buildSetup(systemPrompt, functionDeclarations)
            DebugLog.log("local ws open http=${response.code} -> setup ${setup.length}b")
            webSocket.send(setup)
        }

        // Text frame = a JSON control message. Binary frame = a raw 24 kHz PCM audio chunk.
        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val pcm = bytes.toByteArray()
            if (pcm.isEmpty()) return
            if (!modelGeneratingEmitted) {
                modelGeneratingEmitted = true
                listener.onModelGenerating()
            }
            listener.onAudio(pcm)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log("local ws CLOSING code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLog.log("local ws FAILURE: ${t.message} http=${response?.code}")
            setupDone = false
            modelGeneratingEmitted = false
            listener.onError("Local connection failed: ${t.message ?: t.javaClass.simpleName}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log("local ws closed code=$code reason=$reason")
            setupDone = false
            modelGeneratingEmitted = false
            listener.onClosed()
        }
    }

    /** Parse the message (pure) and relay each event to [listener], tracking the setup gate. */
    private fun handleMessage(text: String) {
        for (event in parseServerMessage(text)) {
            when (event) {
                is ServerEvent.Ready -> {
                    setupDone = true
                    DebugLog.log("local ready")
                    listener.onReady()
                }

                is ServerEvent.InputTranscript -> listener.onInputTranscript(event.text)

                is ServerEvent.OutputTranscript -> listener.onOutputTranscript(event.text)

                is ServerEvent.ModelGenerating -> {
                    modelGeneratingEmitted = true
                    listener.onModelGenerating()
                }

                is ServerEvent.TurnComplete -> {
                    modelGeneratingEmitted = false
                    listener.onTurnComplete()
                }

                is ServerEvent.Interrupted -> listener.onInterrupted()

                is ServerEvent.ToolCall -> {
                    DebugLog.log("local toolCall ids=${event.calls.map { it.id }}")
                    listener.onToolCall(event.calls)
                }

                is ServerEvent.ToolCancel -> {
                    DebugLog.log("local toolCancel ids=${event.ids}")
                    listener.onToolCancel(event.ids)
                }

                is ServerEvent.Error -> listener.onError(event.message)
            }
        }
    }

    /** One decoded thing the host told us. Audio arrives as binary frames, so it isn't a [ServerEvent]. */
    sealed interface ServerEvent {
        data object Ready : ServerEvent
        data class InputTranscript(val text: String) : ServerEvent
        data class OutputTranscript(val text: String) : ServerEvent
        data object ModelGenerating : ServerEvent
        data object TurnComplete : ServerEvent
        data object Interrupted : ServerEvent
        data class ToolCall(val calls: List<FunctionCall>) : ServerEvent
        data class ToolCancel(val ids: List<String>) : ServerEvent
        data class Error(val message: String) : ServerEvent
    }

    companion object {
        /**
         * Adapts the neutral [BackendConfig][com.portal.assistant.conversation.backend.BackendConfig] onto
         * this client: the generic `credential` carries the host URL, and `model` is ignored (the host owns
         * which local model runs). Selected in [Backends][com.portal.assistant.conversation.backend.Backends].
         */
        val Factory = VoiceBackendFactory { config, listener ->
            LocalBackend(
                hostUrl = config.credential.orEmpty(),
                systemPrompt = config.systemPrompt,
                functionDeclarations = config.functionDeclarations,
                listener = listener,
            )
        }

        /** Resolve [host] to a canonical `wss://` URL via [LocalVoiceHost], or null if invalid. */
        fun parseWssUrl(host: String): String? = when (val parsed = LocalVoiceHost.parse(host)) {
            is LocalVoiceHost.ParseResult.Ok -> parsed.wssUrl
            is LocalVoiceHost.ParseResult.Invalid -> null
        }

        /** The `setup` frame: system prompt + tool declarations (normalized to standard JSON Schema). Pure. */
        fun buildSetup(systemPrompt: String, functionDeclarations: List<JSONObject> = emptyList()): String {
            val tools = JSONArray()
            LocalToolMapping.normalize(functionDeclarations).forEach { tools.put(it) }
            return JSONObject()
                .put("type", "setup")
                .put("systemPrompt", systemPrompt)
                .put("tools", tools)
                .toString()
        }

        /** One complete text turn as the user (chip-send). Pure. */
        fun buildUserText(text: String): String = JSONObject().put("type", "user_text").put("text", text).toString()

        /** Tool results, order-matched to the original tool_call. Pure. */
        fun buildToolResult(results: List<ToolResult>): String {
            val arr = JSONArray()
            results.forEach { r ->
                arr.put(JSONObject().put("id", r.id).put("name", r.name).put("response", r.response))
            }
            return JSONObject().put("type", "tool_result").put("results", arr).toString()
        }

        /**
         * Parse one host message into the [ServerEvent]s it carries. Pure (no Android, no I/O), so it is
         * unit-tested. Unparseable input or an unknown `type` → empty list.
         */
        fun parseServerMessage(text: String): List<ServerEvent> {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            return when (json.optString("type")) {
                "ready" -> listOf(ServerEvent.Ready)

                "input_transcript" ->
                    json.optString("text").takeIf { it.isNotEmpty() }?.let { listOf(ServerEvent.InputTranscript(it)) } ?: emptyList()

                "output_transcript" ->
                    json.optString("text").takeIf { it.isNotEmpty() }?.let { listOf(ServerEvent.OutputTranscript(it)) } ?: emptyList()

                "model_generating" -> listOf(ServerEvent.ModelGenerating)

                "turn_complete" -> listOf(ServerEvent.TurnComplete)

                "interrupted" -> listOf(ServerEvent.Interrupted)

                "error" -> listOf(ServerEvent.Error(json.optString("message").ifEmpty { "Local server error" }))

                "tool_call" -> parseToolCall(json)

                "tool_cancel" -> parseToolCancel(json)

                else -> emptyList()
            }
        }

        private fun parseToolCall(json: JSONObject): List<ServerEvent> {
            val arr = json.optJSONArray("calls") ?: return emptyList()
            val calls = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val rawArgs = o.opt("args")
                val args = when (rawArgs) {
                    is JSONObject -> rawArgs
                    is String -> runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject() }
                    else -> JSONObject()
                }
                FunctionCall(o.optString("id"), name, args)
            }
            return if (calls.isEmpty()) emptyList() else listOf(ServerEvent.ToolCall(calls))
        }

        private fun parseToolCancel(json: JSONObject): List<ServerEvent> {
            val arr = json.optJSONArray("ids") ?: return emptyList()
            val ids = (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
            return if (ids.isEmpty()) emptyList() else listOf(ServerEvent.ToolCancel(ids))
        }
    }
}
