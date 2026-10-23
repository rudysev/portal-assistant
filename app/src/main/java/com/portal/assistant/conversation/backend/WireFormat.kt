package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.ToolResult

/**
 * What one voice backend's frames look like — the only part that differs between backends. Pure (no socket,
 * no threads, no Android): [WebSocketVoiceBackend] owns the socket, the setup gate and the relay, and asks this
 * for each outgoing [Frame] and for the [BackendEvent]s each incoming frame carries. A wire format is built per
 * session, so [setup] already knows its session inputs.
 */
interface WireFormat {

    /** Short name for log lines and the connection-failure text ("Local" → "Local connection failed: …"). */
    val label: String

    /** The first frame, sent as soon as the socket opens. */
    fun setup(): Frame

    /** One chunk of 16 kHz mono 16-bit PCM mic audio. */
    fun audio(pcm: ByteArray): Frame

    /** One complete text turn as the user. */
    fun userText(text: String): Frame

    /** Tool results, order-matched to the tool call they answer. */
    fun toolResults(results: List<ToolResult>): Frame

    /** The events one incoming text frame carries, in order; unparseable or unknown input → empty. */
    fun decode(text: String): List<BackendEvent>

    /** The events one incoming binary frame carries, in order. */
    fun decode(bytes: ByteArray): List<BackendEvent>

    /**
     * The words meant for the user in the server's close frame ([code], [reason]) — reported as a
     * [BackendError.Server] — or null when this backend's close reasons aren't (a normal close, a blank reason, or
     * a backend that reports its errors as frames instead).
     */
    fun closeMessage(code: Int, reason: String): String?
}

/** One outgoing WebSocket frame. */
sealed interface Frame {
    data class Text(val text: String) : Frame

    /** Compared by content so encode tests can assert on it. */
    class Binary(val bytes: ByteArray) : Frame {
        override fun equals(other: Any?) = other is Binary && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        override fun toString() = "Binary(${bytes.size}b)"
    }
}
