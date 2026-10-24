package com.portal.assistant.local

import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.BackendError
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.Frame
import com.portal.assistant.conversation.backend.ToolCallWire
import com.portal.assistant.conversation.backend.WireFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format of a **local voice host** on the LAN — our own minimal, vendor-agnostic protocol, modeled on
 * neither Gemini's nor OpenAI's. The host does everything Gemini's server did for free — VAD, ASR, the LLM
 * (tool-calling), TTS, and web-search grounding — so the app just ships audio and control frames:
 *  - **binary frame** = one raw PCM chunk (16 kHz up / 24 kHz down) — no base64, it's a LAN socket.
 *  - **text frame (JSON)** = control, keyed by `type`. `input_transcript` and `output_transcript` carry
 *    **deltas** (new text since the last frame for that side), not the full utterance — the engine accumulates
 *    them into the chat bubble, same as Gemini Live.
 *
 * The host should emit `model_generating` before its first audio frame; the transport reports it anyway if it
 * doesn't. Pure — the socket is
 * [WebSocketVoiceBackend][com.portal.assistant.conversation.backend.WebSocketVoiceBackend]'s.
 */
class LocalWireFormat(
    private val systemPrompt: String,
    private val functionDeclarations: List<JSONObject> = emptyList(),
) : WireFormat {

    override val label = "Local"

    /** System prompt + tool declarations (normalized to standard JSON Schema). */
    override fun setup(): Frame {
        val tools = JSONArray()
        LocalToolMapping.normalize(functionDeclarations).forEach { tools.put(it) }
        return JSONObject()
            .put("type", "setup")
            .put("systemPrompt", systemPrompt)
            .put("tools", tools)
            .toFrame()
    }

    override fun audio(pcm: ByteArray): Frame = Frame.Binary(pcm)

    override fun userText(text: String): Frame = JSONObject().put("type", "user_text").put("text", text).toFrame()

    override fun toolResults(results: List<ToolResult>): Frame {
        val elements = JSONArray()
        results.forEach { elements.put(ToolCallWire.resultElement(it)) }
        return JSONObject().put("type", "tool_result").put("results", elements).toFrame()
    }

    /** One JSON control frame; unparseable input or an unknown `type` → empty. */
    override fun decode(text: String): List<BackendEvent> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val type = json.optString("type")
        return SIGNALS[type]?.let { listOf(it) } ?: payloadEvents(type, json)
    }

    /** The frame types that carry data; an unknown type → empty. */
    private fun payloadEvents(type: String, json: JSONObject): List<BackendEvent> = when (type) {
        "input_transcript" -> ifPresent(json.optString("text"), BackendEvent::InputTranscript)

        "output_transcript" -> ifPresent(json.optString("text"), BackendEvent::OutputTranscript)

        "error" -> listOf(
            BackendEvent.Failed(BackendError.Server(json.optString("message").ifEmpty { "Local server error" })),
        )

        "tool_call" -> ifPresent(ToolCallWire.parseCalls(json.optJSONArray("calls")), BackendEvent::ToolCall)

        "tool_cancel" -> ifPresent(ToolCallWire.parseCancelIds(json.optJSONArray("ids")), BackendEvent::ToolCancel)

        else -> emptyList()
    }

    /** [event] of a non-empty text payload; nothing for an empty one. */
    private fun ifPresent(text: String, event: (String) -> BackendEvent): List<BackendEvent> =
        if (text.isEmpty()) emptyList() else listOf(event(text))

    /** [event] of a non-empty list payload; nothing for an empty one. */
    private fun <T> ifPresent(items: List<T>, event: (List<T>) -> BackendEvent): List<BackendEvent> =
        if (items.isEmpty()) emptyList() else listOf(event(items))

    /** One raw 24 kHz PCM chunk. */
    override fun decode(bytes: ByteArray): List<BackendEvent> =
        if (bytes.isEmpty()) emptyList() else listOf(BackendEvent.Audio(bytes))

    /** The host reports its errors as `error` frames; a close reason is never meant for the user. */
    override fun closeMessage(code: Int, reason: String): String? = null

    private fun JSONObject.toFrame() = Frame.Text(toString())
}

/** The frame types that carry no data, each one event. */
private val SIGNALS: Map<String, BackendEvent> = mapOf(
    "ready" to BackendEvent.Ready,
    "model_generating" to BackendEvent.ModelGenerating,
    "turn_complete" to BackendEvent.TurnComplete,
    "interrupted" to BackendEvent.Interrupted,
)
