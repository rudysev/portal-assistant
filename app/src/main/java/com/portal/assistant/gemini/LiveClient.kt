package com.portal.assistant.gemini

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import com.portal.commons.PcmCaptureFormat
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Client for the **Gemini Live API** (BidiGenerateContent over a WebSocket).
 *
 * A real-time, bidirectional voice session: we stream raw 16 kHz PCM mic audio up, and the model
 * streams native 24 kHz PCM speech back, plus running transcripts of both sides. The server does the
 * voice-activity detection, so it decides when the user's turn ends and starts replying. The
 * `googleSearch` tool is enabled for live weather/news grounding; [functionDeclarations] adds
 * on-device function calling (Phase 2).
 *
 * Scope: this class is just the WebSocket + JSON boundary. The conversation logic lives in the
 * [com.portal.assistant.conversation] reducer/engine. Inbound parsing is the pure, unit-tested
 * [parseServerMessage]; the socket callbacks just feed it and relay [ServerEvent]s to [listener].
 *
 * Auth: the API key is the `?key=` query param (fine for this private appliance). Callbacks arrive on
 * OkHttp's thread; the engine marshals them onto its single orchestration thread.
 */
class LiveClient(
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val functionDeclarations: List<JSONObject> = emptyList(),
    private val wsBase: String = WS_BASE,
    private val listener: VoiceBackend.Listener,
) : VoiceBackend {

    private val http = Http.shared.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep the socket open
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null

    @Volatile private var setupDone = false

    override fun connect() {
        if (apiKey.isBlank()) {
            listener.onError("No Gemini API key is set. Add one in Settings → API key.")
            return
        }
        val request = Request.Builder().url("$wsBase?key=$apiKey").build()
        DebugLog.log("live connecting model=$model")
        ws = http.newWebSocket(request, socketListener)
    }

    /** Stream one chunk of 16 kHz mono 16-bit PCM mic audio to the model (the [VoiceBackend] audio contract). */
    override fun sendAudio(pcm: ByteArray) {
        val sock = ws ?: return
        if (!setupDone) return
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val mime = "audio/pcm;rate=${PcmCaptureFormat.SAMPLE_RATE}"
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(JSONObject().put("mimeType", mime).put("data", b64)),
            ),
        )
        sock.send(msg.toString())
    }

    /**
     * Send one complete text turn as the user (e.g. a tapped suggestion). `turnComplete = true` tells the
     * server the user's turn is finished, so the model answers immediately — no VAD/end-of-speech wait.
     */
    override fun sendText(text: String) {
        val sock = ws
        if (sock == null || !setupDone) {
            DebugLog.log("sendText dropped (ws=${sock != null}, setupDone=$setupDone)")
            return
        }
        sock.send(buildClientText(text))
    }

    override fun close() {
        runCatching { ws?.close(1000, "client closing") }
        ws = null
        setupDone = false
    }

    /** Send tool results back to the model. [results] order must match the original functionCalls order. */
    override fun sendToolResponse(results: List<ToolResult>) {
        val sock = ws ?: return
        val responses = JSONArray()
        results.forEach { r ->
            responses.put(JSONObject().put("id", r.id).put("name", r.name).put("response", r.response))
        }
        sock.send(
            JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString(),
        )
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setup = buildSetup(model, systemPrompt, functionDeclarations)
            DebugLog.log("live ws open http=${response.code} -> setup ${setup.length}b")
            webSocket.send(setup)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handleMessage(bytes.utf8())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log("live ws CLOSING code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLog.log("live ws FAILURE: ${t.message} http=${response?.code}")
            setupDone = false
            listener.onError("Live connection failed: ${t.message ?: t.javaClass.simpleName}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log("live ws closed code=$code reason=$reason")
            setupDone = false
            listener.onClosed()
        }
    }

    /** Parse the message (pure) and relay each event to [listener], tracking the setup gate. */
    private fun handleMessage(text: String) {
        for (event in parseServerMessage(text)) {
            when (event) {
                is ServerEvent.Ready -> {
                    setupDone = true
                    DebugLog.log("live setupComplete")
                    listener.onReady()
                }

                is ServerEvent.InputTranscript -> listener.onInputTranscript(event.text)

                is ServerEvent.OutputTranscript -> listener.onOutputTranscript(event.text)

                is ServerEvent.Audio -> listener.onAudio(event.pcm24k)

                is ServerEvent.ModelGenerating -> listener.onModelGenerating()

                is ServerEvent.TurnComplete -> listener.onTurnComplete()

                is ServerEvent.Interrupted -> listener.onInterrupted()

                is ServerEvent.ToolCall -> {
                    DebugLog.log("live toolCall ids=${event.calls.map { it.id }}")
                    listener.onToolCall(event.calls)
                }

                is ServerEvent.ToolCancel -> {
                    DebugLog.log("live toolCancel ids=${event.ids}")
                    listener.onToolCancel(event.ids)
                }

                is ServerEvent.GoAway -> listener.onServerClosingSoon(event.timeLeftMs)
            }
        }
    }

    /** One decoded thing the server told us, in the order it appeared in a message. */
    sealed interface ServerEvent {
        data object Ready : ServerEvent
        data class InputTranscript(val text: String) : ServerEvent
        data class OutputTranscript(val text: String) : ServerEvent
        class Audio(val pcm24k: ByteArray) : ServerEvent
        data object ModelGenerating : ServerEvent
        data object TurnComplete : ServerEvent
        data object Interrupted : ServerEvent
        data class ToolCall(val calls: List<FunctionCall>) : ServerEvent
        data class ToolCancel(val ids: List<String>) : ServerEvent
        data class GoAway(val timeLeftMs: Long) : ServerEvent
    }

    companion object {
        const val WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Server-side VAD tuning. The silence window dictates how long the user must remain silent before
        // the model considers their turn "complete" and starts generating its response. 600 ms stays safe for
        // a mid-clause breath only because endOfSpeechSensitivity is END_SENSITIVITY_LOW below — raise this
        // window if you ever raise that sensitivity. Tune on device.
        const val VAD_PREFIX_PADDING_MS = 300
        const val VAD_SILENCE_DURATION_MS = 600

        /**
         * One complete text turn as the user. `turnComplete = true` finishes the user's turn so the model
         * answers immediately (no VAD/end-of-speech wait). Pure builder, unit-tested like [buildSetup].
         */
        fun buildClientText(text: String): String = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text))),
                    ),
                )
                .put("turnComplete", true),
        ).toString()

        /**
         * Build the `setup` message (pure, so it's unit-testable): AUDIO responses, the system
         * instruction, `googleSearch` grounding plus optional [functionDeclarations] (never emits
         * an empty `functionDeclarations` array — the Live API rejects it), input- and output-audio
         * transcription (drives the user-speech signal and the model's word-by-word chat text), and
         * the server VAD config.
         */
        fun buildSetup(
            model: String,
            systemPrompt: String,
            functionDeclarations: List<JSONObject> = emptyList(),
        ): String = JSONObject().put(
            "setup",
            JSONObject().apply {
                put("model", if (model.startsWith("models/")) model else "models/$model")
                put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                val toolsArray = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
                if (functionDeclarations.isNotEmpty()) {
                    val declArray = JSONArray()
                    functionDeclarations.forEach { declArray.put(it) }
                    toolsArray.put(JSONObject().put("functionDeclarations", declArray))
                }
                put("tools", toolsArray)
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                put(
                    "realtimeInputConfig",
                    JSONObject().put(
                        "automaticActivityDetection",
                        JSONObject()
                            .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                            .put("prefixPaddingMs", VAD_PREFIX_PADDING_MS)
                            .put("silenceDurationMs", VAD_SILENCE_DURATION_MS),
                    ),
                )
            },
        ).toString()

        /**
         * Parse one server message into the [ServerEvent]s it carries, in document order. Pure (no
         * Android, no I/O) so it is unit-tested. Unparseable input → empty list.
         *
         * `toolCall` and `toolCallCancellation` are top-level siblings of `serverContent` — the same
         * message can carry both, so we collect all events and return them merged in document order.
         */
        fun parseServerMessage(text: String): List<ServerEvent> {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()

            if (json.has("setupComplete")) return listOf(ServerEvent.Ready)

            json.optJSONObject("goAway")?.let { ga ->
                return listOf(ServerEvent.GoAway(parseDurationMs(ga.optString("timeLeft"))))
            }

            val events = mutableListOf<ServerEvent>()

            // Tool events are top-level — must be parsed BEFORE the serverContent guard below.
            json.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { arr ->
                val calls = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val rawArgs = o.opt("args")
                    val args = when (rawArgs) {
                        is JSONObject -> rawArgs
                        is String -> runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject() }
                        else -> JSONObject()
                    }
                    FunctionCall(o.optString("id"), o.optString("name"), args)
                }
                if (calls.isNotEmpty()) events += ServerEvent.ToolCall(calls)
            }
            json.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { arr ->
                val ids = (0 until arr.length()).mapNotNull { s ->
                    arr.optString(s).takeIf { it.isNotEmpty() }
                }
                if (ids.isNotEmpty()) events += ServerEvent.ToolCancel(ids)
            }

            val server = json.optJSONObject("serverContent") ?: return events.ifEmpty { emptyList() }

            server.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                ?.let { events.add(ServerEvent.InputTranscript(it)) }

            if (server.optBoolean("interrupted", false)) events.add(ServerEvent.Interrupted)

            val parts = server.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                events.add(ServerEvent.ModelGenerating)
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                    if (data.isNotEmpty()) {
                        runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
                            ?.let { events.add(ServerEvent.Audio(it)) }
                    }
                }
            }

            server.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
                ?.let { events.add(ServerEvent.OutputTranscript(it)) }

            if (server.optBoolean("turnComplete", false)) events.add(ServerEvent.TurnComplete)

            return events
        }

        /** Parse a protobuf Duration string like "9.5s" to millis; 0 if unparseable. */
        fun parseDurationMs(s: String): Long = (s.trim().removeSuffix("s").toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
    }
}
