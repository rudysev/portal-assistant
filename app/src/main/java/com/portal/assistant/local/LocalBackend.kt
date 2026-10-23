package com.portal.assistant.local

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.LocalTlsMode
import com.portal.assistant.conversation.backend.LocalWireOptions
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory
import com.portal.assistant.conversation.backend.WebSocketVoiceBackend
import com.portal.assistant.util.Http
import okhttp3.OkHttpClient

/**
 * The **local voice host** backend: a [LocalWireFormat] on the shared [WebSocketVoiceBackend] transport.
 * Auth: none — the "credential" is the host's canonical `wss://` URL (canonicalized by [LocalVoiceHost] when
 * Settings stores it; a blank one is refused by the conversation opening). Selected in
 * [Backends][com.portal.assistant.conversation.backend.Backends].
 */
object LocalBackend {

    /**
     * A LAN pipeline can sit silent longer than Gemini's co-streamed first audio — STT, LLM, on-device
     * tool round-trips, optional web search, and local TTS all precede the first PCM chunk.
     */
    const val DEAD_AIR_STALL_MS = 30_000L

    /** DNS hostname of the stored [wssUrl] for [Backends.warmDns][com.portal.assistant.conversation.backend.Backends.warmDns], or null if invalid. */
    fun dnsHost(wssUrl: String): String? = LocalVoiceHost.dnsName(wssUrl)

    /** The base client for [LocalWireOptions.tls]: trust the host's self-signed cert, or the device trust store. */
    internal fun tlsClient(wire: LocalWireOptions): OkHttpClient = when (wire.tls) {
        LocalTlsMode.TRUST_SELF_SIGNED -> Http.lanVoice
        LocalTlsMode.SYSTEM -> Http.shared
    }

    /**
     * Adapts the neutral [BackendConfig] onto the transport: the generic `credential` carries the host URL, and
     * `model` is ignored (the host owns which local model runs).
     */
    val Factory: VoiceBackendFactory = object : VoiceBackendFactory {
        override val deadAirStallMs = DEAD_AIR_STALL_MS

        override fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend = WebSocketVoiceBackend(
            wireFormat = LocalWireFormat(config.systemPrompt, config.functionDeclarations),
            address = config.credential.orEmpty(),
            listener = listener,
            tls = tlsClient(config.local),
        )
    }
}
