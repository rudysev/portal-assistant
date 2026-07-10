package com.portal.assistant.gemini

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory

/**
 * Adapts the neutral [BackendConfig] onto [LiveClient]'s constructor — the generic
 * [BackendConfig.credential] is Gemini's API key; [BackendConfig.gemini] carries wire options (endpoint
 * override when added). Selected in [Backends][com.portal.assistant.conversation.backend.Backends].
 */
object GeminiBackend {

    /** DNS hostname for [Backends.warmDns] — derived from [LiveClient.WS_BASE], not duplicated here. */
    fun dnsHost(): String = java.net.URI(LiveClient.WS_BASE).host

    /** Gemini co-streams first audio quickly — a shorter dead-air budget is enough. */
    const val DEAD_AIR_STALL_MS = 10_000L

    object Factory : VoiceBackendFactory {
        override val deadAirStallMs = DEAD_AIR_STALL_MS

        override fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend {
            val endpoint = config.gemini.endpointUrl ?: LiveClient.WS_BASE
            return LiveClient(
                apiKey = config.credential.orEmpty(),
                model = config.model,
                systemPrompt = config.systemPrompt,
                functionDeclarations = config.functionDeclarations,
                wsBase = endpoint,
                listener = listener,
            )
        }
    }
}
