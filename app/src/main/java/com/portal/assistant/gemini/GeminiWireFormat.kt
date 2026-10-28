package com.portal.assistant.gemini

import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.Frame
import com.portal.assistant.conversation.backend.ToolCallWire
import com.portal.assistant.conversation.backend.WireFormat
import com.portal.commons.PcmCaptureFormat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The wire format of the **Gemini Live API** (BidiGenerateContent over a WebSocket).
 *
 * A real-time, bidirectional voice session: we stream raw 16 kHz PCM mic audio up, and the model
 * streams native 24 kHz PCM speech back, plus running transcripts of both sides. The server does the
 * voice-activity detection, so it decides when the user's turn ends and starts replying. [googleSearch] asks for
 * Google Search (live weather/news) on a model that offers it; [functionDeclarations] adds on-device function
 * calling.
 *
 * Every frame is JSON text, audio base64 inside it. Pure — the socket is
 * [WebSocketVoiceBackend][com.portal.assistant.conversation.backend.WebSocketVoiceBackend]'s, and the connect
 * address (endpoint + API key) is [GeminiBackend.Factory]'s.
 */
class GeminiWireFormat(
    private val model: String,
    private val systemPrompt: String,
    private val functionDeclarations: List<JSONObject> = emptyList(),
    /** The Chosen speaker (a [GeminiSpeaker] name); null = Server default. */
    private val speaker: String? = null,
    /** Whether setup asks for Google Search — [GeminiModel.Model.googleSearch]. */
    private val googleSearch: Boolean = true,
) : WireFormat {

    override val label = "Live"

    /**
     * The `setup` frame: AUDIO responses, the system instruction, `googleSearch` when [googleSearch] plus optional
     * [functionDeclarations] (never an empty `tools` or `functionDeclarations` array — the Live API rejects both),
     * input- and output-audio transcription (drives the user-speech signal and the model's word-by-word chat
     * text), and the server VAD config. A chosen [speaker] adds
     * `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`; null = Server default, which leaves
     * `speechConfig` out entirely so Gemini picks its own voice.
     */
    override fun setup(): Frame = JSONObject().put(
        "setup",
        JSONObject().apply {
            put("model", if (model.startsWith("models/")) model else "models/$model")
            val generationConfig = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
            if (speaker != null) {
                generationConfig.put(
                    "speechConfig",
                    JSONObject().put(
                        "voiceConfig",
                        JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", speaker)),
                    ),
                )
            }
            put("generationConfig", generationConfig)
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            val toolsArray = JSONArray()
            if (googleSearch) toolsArray.put(JSONObject().put("googleSearch", JSONObject()))
            if (functionDeclarations.isNotEmpty()) {
                val declarationsArray = JSONArray()
                functionDeclarations.forEach { declarationsArray.put(it) }
                toolsArray.put(JSONObject().put("functionDeclarations", declarationsArray))
            }
            if (toolsArray.length() > 0) put("tools", toolsArray)
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
    ).toFrame()

    /**
     * One chunk of 16 kHz mono 16-bit PCM mic audio, base64 in `realtimeInput.audio`. Not the deprecated
     * `mediaChunks`: Gemini 3.8 ignores it (device-checked 2026-10-27).
     */
    override fun audio(pcm: ByteArray): Frame {
        val base64Pcm = Base64.getEncoder().encodeToString(pcm)
        val mimeType = "audio/pcm;rate=${PcmCaptureFormat.SAMPLE_RATE}"
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", JSONObject().put("mimeType", mimeType).put("data", base64Pcm)),
        ).toFrame()
    }

    /**
     * One text turn as the user (e.g. a tapped suggestion), as `realtimeInput.text`: the model answers it like a
     * finished spoken turn. Not a `clientContent` turn: Gemini 3.8 takes clientContent only as initial history and
     * never answers it (device-checked 2026-10-27).
     */
    override fun userText(text: String): Frame =
        JSONObject().put("realtimeInput", JSONObject().put("text", text)).toFrame()

    /** Tool results as `toolResponse.functionResponses`, in the original functionCalls order. */
    override fun toolResults(results: List<ToolResult>): Frame {
        val responses = JSONArray()
        results.forEach { responses.put(ToolCallWire.resultElement(it)) }
        return JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toFrame()
    }

    /**
     * One server message into the [BackendEvent]s it carries, in document order; unparseable input → empty.
     * `goAway` is [BackendEvent.ClosingSoon].
     *
     * `toolCall` and `toolCallCancellation` are top-level siblings of `serverContent` — the same message can
     * carry both, so we collect all events and return them merged in document order.
     */
    override fun decode(text: String): List<BackendEvent> {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val goAway = json?.optJSONObject("goAway")
        return when {
            json == null -> emptyList()
            json.has("setupComplete") -> listOf(BackendEvent.Ready)
            goAway != null -> listOf(BackendEvent.ClosingSoon(parseDurationMs(goAway.optString("timeLeft"))))
            else -> contentEvents(json)
        }
    }

    /** The tool and `serverContent` events of one message, in document order. */
    private fun contentEvents(json: JSONObject): List<BackendEvent> {
        val events = mutableListOf<BackendEvent>()

        // Tool events are top-level, so they are read whether or not the message carries serverContent.
        ToolCallWire.parseCalls(json.optJSONObject("toolCall")?.optJSONArray("functionCalls"))
            .takeIf { it.isNotEmpty() }?.let { events += BackendEvent.ToolCall(it) }
        ToolCallWire.parseCancelIds(json.optJSONObject("toolCallCancellation")?.optJSONArray("ids"))
            .takeIf { it.isNotEmpty() }?.let { events += BackendEvent.ToolCancel(it) }

        json.optJSONObject("serverContent")?.let { addServerContent(it, events) }
        return events
    }

    private fun addServerContent(serverContent: JSONObject, events: MutableList<BackendEvent>) {
        serverContent.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { events.add(BackendEvent.InputTranscript(it)) }

        if (serverContent.optBoolean("interrupted", false)) events.add(BackendEvent.Interrupted)

        val parts = serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null && parts.length() > 0) addModelTurn(parts, events)

        serverContent.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }
            ?.let { events.add(BackendEvent.OutputTranscript(it)) }

        if (serverContent.optBoolean("turnComplete", false)) events.add(BackendEvent.TurnComplete)
    }

    /** The model turn has started; its parts carry the base64 audio chunks. */
    private fun addModelTurn(parts: JSONArray, events: MutableList<BackendEvent>) {
        events.add(BackendEvent.ModelGenerating)
        for (i in 0 until parts.length()) {
            val base64Audio = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
            if (base64Audio.isNotEmpty()) {
                runCatching { Base64.getMimeDecoder().decode(base64Audio) }.getOrNull()
                    ?.let { events.add(BackendEvent.Audio(it)) }
            }
        }
    }

    /** Gemini sends JSON in binary frames too: read the bytes as UTF-8 and decode them as text. */
    override fun decode(bytes: ByteArray): List<BackendEvent> = decode(String(bytes, Charsets.UTF_8))

    /** Gemini says why it ends a session in the close reason (e.g. a rejected API key); a normal close says nothing. */
    override fun closeMessage(code: Int, reason: String): String? = reason.takeIf {
        code != NORMAL_CLOSURE &&
            it.isNotBlank()
    }

    private fun JSONObject.toFrame() = Frame.Text(toString())

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val MS_PER_SEC = 1000

        // Server-side VAD tuning. The silence window dictates how long the user must remain silent before
        // the model considers their turn "complete" and starts generating its response. 600 ms stays safe for
        // a mid-clause breath only because endOfSpeechSensitivity is END_SENSITIVITY_LOW below — raise this
        // window if you ever raise that sensitivity. Tune on device.
        const val VAD_PREFIX_PADDING_MS = 300
        const val VAD_SILENCE_DURATION_MS = 600

        /** Parse a protobuf Duration string like "9.5s" to millis; 0 if unparseable. */
        fun parseDurationMs(durationText: String): Long =
            (durationText.trim().removeSuffix("s").toDoubleOrNull()?.times(MS_PER_SEC))?.toLong() ?: 0L
    }
}
