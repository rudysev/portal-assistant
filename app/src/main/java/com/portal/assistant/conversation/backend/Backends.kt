package com.portal.assistant.conversation.backend

import com.portal.assistant.gemini.GeminiBackend

/**
 * The single composition point that selects the concrete [VoiceBackend] implementation — deliberately
 * the *only* place in [conversation][com.portal.assistant.conversation] that imports `gemini`, so the
 * engine and the pure reducer stay backend-agnostic.
 *
 * Today there is one backend (Gemini Live). When a local/LAN backend lands, choose here (e.g. by a
 * user pref) and everything upstream is unchanged.
 */
object Backends {
    val default: VoiceBackendFactory = GeminiBackend.Factory
}
