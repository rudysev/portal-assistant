package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall

/**
 * One thing a voice backend told us, in the order it appeared — the neutral event every [WireFormat] decodes into
 * and [WebSocketVoiceBackend] hands to [VoiceBackend.Listener.onEvent] as is, alongside its own socket outcomes
 * ([Failed] with a [BackendError.Transport], [Closed]). A wire format simply never produces the events its backend
 * lacks, and leaves [Closed] to the transport.
 */
sealed interface BackendEvent {
    /** The session is set up and ready; the transport opens its setup gate on it. */
    data object Ready : BackendEvent

    /** A delta of the user's transcribed speech. */
    data class InputTranscript(val text: String) : BackendEvent

    /**
     * The backend's own VAD heard the user start or stop speaking — no text. Only a backend whose transcript lags the
     * speech sends it (the local host: VAD stop + Whisper), so the no-speech window counts from the speech itself.
     */
    data object SpeechActivity : BackendEvent

    /** A delta of the model's own words, transcribed as it speaks. */
    data class OutputTranscript(val text: String) : BackendEvent

    /** 24 kHz mono 16-bit PCM — compared by content so decode tests can assert on it. */
    class Audio(val pcm24k: ByteArray) : BackendEvent {
        override fun equals(other: Any?) = other is Audio && pcm24k.contentEquals(other.pcm24k)
        override fun hashCode() = pcm24k.contentHashCode()
        override fun toString() = "Audio(${pcm24k.size}b)"
    }

    /**
     * The model has started this turn — on its first content, including a text-only "thinking" part before any
     * audio (e.g. while it runs a search). Always before the turn's first [Audio] (see [VoiceBackend]).
     */
    data object ModelGenerating : BackendEvent
    data object TurnComplete : BackendEvent

    /** The model's in-progress turn was interrupted (barge-in / server cut). */
    data object Interrupted : BackendEvent

    /** The model wants on-device tools run; the engine answers with [VoiceBackend.sendToolResponse]. */
    data class ToolCall(val calls: List<FunctionCall>) : BackendEvent

    /** The model cancelled the listed tool calls (ids). */
    data class ToolCancel(val ids: List<String>) : BackendEvent

    /** The server will end this session within about [graceMs]. A backend without a bounded session never says so. */
    data class ClosingSoon(val graceMs: Long) : BackendEvent

    /**
     * The session failed (see [BackendError] for the two kinds): a wire format reports the server's own words as a
     * [BackendError.Server]; the transport reports a broken socket as a [BackendError.Transport].
     */
    data class Failed(val error: BackendError) : BackendEvent

    /** The socket closed — reported by the transport only. */
    data object Closed : BackendEvent
}
