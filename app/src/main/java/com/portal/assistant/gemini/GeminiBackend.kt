package com.portal.assistant.gemini

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Adapts the neutral [BackendConfig] onto [LiveClient]'s constructor — the generic
 * [BackendConfig.credential] is Gemini's API key; [BackendConfig.speaker] is the Chosen speaker (null =
 * Server default); [BackendConfig.gemini] carries wire options (an endpoint override). Every Gemini session — a
 * conversation or a Speaker preview — is built here. Selected in [Backends][com.portal.assistant.conversation.backend.Backends].
 */
object GeminiBackend {

    /** DNS hostname for [Backends.warmDns] — derived from [LiveClient.WS_BASE], not duplicated here. */
    fun dnsHost(): String = java.net.URI(LiveClient.WS_BASE).host

    /** Gemini co-streams first audio quickly — a shorter dead-air budget is enough. */
    const val DEAD_AIR_STALL_MS = 10_000L

    /**
     * [endpoint] with [apiKey] added as its URL-encoded `key` query param — via OkHttp's `addQueryParameter`,
     * exactly as [GeminiKeyCheck] encodes it, so a key with reserved characters reaches the server intact.
     * [HttpUrl] only parses http(s), so the `ws(s)` scheme is swapped out and back the way OkHttp's own
     * WebSocket request does.
     */
    private fun connectUrl(endpoint: String, apiKey: String): String {
        val httpEndpoint = endpoint.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http")
        val httpUrlWithKey = httpEndpoint.toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build()
        return httpUrlWithKey.toString().replaceFirst(Regex("^http"), "ws")
    }

    object Factory : VoiceBackendFactory {
        override val deadAirStallMs = DEAD_AIR_STALL_MS

        override fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend {
            val endpoint = config.gemini.endpointUrl ?: LiveClient.WS_BASE
            return LiveClient(
                connectUrl = connectUrl(endpoint, config.credential.orEmpty()),
                model = config.model,
                systemPrompt = config.systemPrompt,
                functionDeclarations = config.functionDeclarations,
                speaker = config.speaker,
                listener = listener,
            )
        }
    }
}
