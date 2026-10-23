package com.portal.assistant.local

import com.portal.assistant.conversation.ToolResult
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
 * doesn't. Pure — the socket is [WebSocketVoiceBackend][com.portal.assistant.conversation.backend.WebSocketVoiceBackend]'s.
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
        return when (json.optString("type")) {
            "ready" -> listOf(BackendEvent.Ready)

            "input_transcript" ->
                json.optString("text").takeIf { it.isNotEmpty() }?.let { listOf(BackendEvent.InputTranscript(it)) } ?: emptyList()

            "output_transcript" ->
                json.optString("text").takeIf { it.isNotEmpty() }?.let { listOf(BackendEvent.OutputTranscript(it)) } ?: emptyList()

            "model_generating" -> listOf(BackendEvent.ModelGenerating)

            "turn_complete" -> listOf(BackendEvent.TurnComplete)

            "interrupted" -> listOf(BackendEvent.Interrupted)

            "error" -> listOf(BackendEvent.Error(json.optString("message").ifEmpty { "Local server error" }))

            "tool_call" -> ToolCallWire.parseCalls(json.optJSONArray("calls"))
                .takeIf { it.isNotEmpty() }?.let { listOf(BackendEvent.ToolCall(it)) } ?: emptyList()

            "tool_cancel" -> parseToolCancel(json)

            else -> emptyList()
        }
    }

    /** One raw 24 kHz PCM chunk. */
    override fun decode(bytes: ByteArray): List<BackendEvent> =
        if (bytes.isEmpty()) emptyList() else listOf(BackendEvent.Audio(bytes))

    private fun parseToolCancel(json: JSONObject): List<BackendEvent> {
        val idElements = json.optJSONArray("ids") ?: return emptyList()
        val ids = (0 until idElements.length()).mapNotNull { i -> idElements.optString(i).takeIf { it.isNotEmpty() } }
        return if (ids.isEmpty()) emptyList() else listOf(BackendEvent.ToolCancel(ids))
    }

    private fun JSONObject.toFrame() = Frame.Text(toString())
}
