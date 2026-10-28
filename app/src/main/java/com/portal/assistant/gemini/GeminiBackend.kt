package com.portal.assistant.gemini

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory
import com.portal.assistant.conversation.backend.WebSocketVoiceBackend
import com.portal.assistant.util.Http
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The **Gemini Live** backend: a [GeminiWireFormat] on the shared [WebSocketVoiceBackend] transport, over the
 * device trust store. The generic [BackendConfig.credential] is Gemini's API key; [BackendConfig.speaker] is the
 * Chosen speaker (null = Server default); [BackendConfig.gemini] carries wire options (an endpoint override).
 * Every Gemini session — a conversation or a Speaker preview — is built here. Selected in
 * [Backends][com.portal.assistant.conversation.backend.Backends].
 */
object GeminiBackend {

    const val DEFAULT_ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /** DNS hostname for [Backends.warmDns] — derived from [DEFAULT_ENDPOINT], not duplicated here. */
    fun dnsHost(): String = java.net.URI(DEFAULT_ENDPOINT).host

    /** Gemini co-streams first audio quickly — a shorter dead-air budget is enough. */
    const val DEAD_AIR_STALL_MS = 10_000L

    /**
     * The session's frames: model, system prompt, tool declarations and Chosen speaker from [config], plus what
     * that model offers ([GeminiModel.of] — an id no longer offered opens as the default's).
     */
    internal fun wireFormat(config: BackendConfig): GeminiWireFormat {
        val model = GeminiModel.of(config.model)
        return GeminiWireFormat(
            model = config.model,
            systemPrompt = config.systemPrompt,
            functionDeclarations = config.functionDeclarations,
            speaker = config.speaker,
            googleSearch = model.googleSearch,
        )
    }

    /**
     * Where [config]'s session connects: the configured endpoint (or [DEFAULT_ENDPOINT]) with the API key added
     * as its URL-encoded `key` query param — via OkHttp's `addQueryParameter`, exactly as [GeminiKeyCheck]
     * encodes it, so a key with reserved characters reaches the server intact. [HttpUrl] only parses http(s),
     * so the `ws(s)` scheme is swapped out and back the way OkHttp's own WebSocket request does.
     */
    internal fun connectAddress(config: BackendConfig): String {
        val endpoint = config.gemini.endpointUrl ?: DEFAULT_ENDPOINT
        val httpEndpoint = endpoint.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
        val httpUrlWithKey = httpEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("key", config.credential.orEmpty())
            .build()
        return httpUrlWithKey.toString().replaceFirst(Regex("^http"), "ws")
    }

    object Factory : VoiceBackendFactory {
        override val deadAirStallMs = DEAD_AIR_STALL_MS

        override fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend =
            WebSocketVoiceBackend(
                wireFormat = wireFormat(config),
                address = connectAddress(config),
                listener = listener,
                tls = Http.shared,
            )
    }
}
