package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall

/**
 * One decoded thing a voice backend told us, in the order it appeared — the neutral event every [WireFormat]
 * decodes into and [WebSocketVoiceBackend] relays to [VoiceBackend.Listener] (one case per callback, bar
 * closed). A wire format simply never produces the events its backend lacks.
 */
sealed interface BackendEvent {
    data object Ready : BackendEvent
    data class InputTranscript(val text: String) : BackendEvent
    data class OutputTranscript(val text: String) : BackendEvent

    /** 24 kHz mono 16-bit PCM — compared by content so decode tests can assert on it. */
    class Audio(val pcm24k: ByteArray) : BackendEvent {
        override fun equals(other: Any?) = other is Audio && pcm24k.contentEquals(other.pcm24k)
        override fun hashCode() = pcm24k.contentHashCode()
        override fun toString() = "Audio(${pcm24k.size}b)"
    }

    data object ModelGenerating : BackendEvent
    data object TurnComplete : BackendEvent
    data object Interrupted : BackendEvent
    data class ToolCall(val calls: List<FunctionCall>) : BackendEvent
    data class ToolCancel(val ids: List<String>) : BackendEvent
    data class ClosingSoon(val graceMs: Long) : BackendEvent
    data class Error(val message: String) : BackendEvent
}
