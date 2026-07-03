package com.portal.assistant.gemini

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.VoiceBackendFactory

/**
 * Adapts the neutral [BackendConfig] onto [LiveClient]'s constructor — the generic
 * [BackendConfig.credential] is Gemini's API key. Selected in
 * [Backends][com.portal.assistant.conversation.backend.Backends].
 */
object GeminiBackend {
    val Factory = VoiceBackendFactory { config, listener ->
        LiveClient(
            apiKey = config.credential.orEmpty(),
            model = config.model,
            systemPrompt = config.systemPrompt,
            functionDeclarations = config.functionDeclarations,
            listener = listener,
        )
    }
}
