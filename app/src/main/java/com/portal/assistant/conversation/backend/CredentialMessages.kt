package com.portal.assistant.conversation.backend

import com.portal.assistant.system.AppPrefs

/**
 * User-facing copy when a conversation can't start because the selected backend has no credential.
 * Lives on the neutral backend seam (not in [Backends] or any concrete implementation).
 */
object CredentialMessages {

    fun missing(kind: AppPrefs.VoiceBackendKind): String = when (kind) {
        AppPrefs.VoiceBackendKind.LOCAL ->
            "Jarvis isn’t set up yet — add your local server address in Settings."

        AppPrefs.VoiceBackendKind.GEMINI ->
            "Jarvis isn’t set up yet — a Gemini API key is missing."
    }
}
