package com.portal.assistant.conversation.backend

import com.portal.assistant.gemini.GeminiBackend

/**
 * The single composition point that selects the concrete [VoiceBackend] implementation — deliberately
 * the *only* place in [conversation][com.portal.assistant.conversation] that imports `gemini`, so the
 * engine and the pure reducer stay backend-agnostic.
 *
 * Today there is one backend (Gemini Live). When the local/LAN backend lands, choose here from
 * [AppPrefs.voiceBackendKind] (opt-in for users running their own model) and everything upstream is unchanged.
 */
object Backends {
    val default: VoiceBackendFactory = GeminiBackend.Factory
}
