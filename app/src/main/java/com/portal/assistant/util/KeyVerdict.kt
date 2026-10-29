package com.portal.assistant.util

/** What checking one of the user's own keys (the Gemini API key, the Search key) against its service said. */
sealed interface KeyVerdict {
    /** The key authenticated — the service accepted it. */
    data object Valid : KeyVerdict

    /** The service rejected the key (bad, revoked, or not enabled for it). */
    data object Invalid : KeyVerdict

    /** No verdict (offline, throttled, server trouble) — don't treat it as a bad key. */
    data object NetworkError : KeyVerdict
}
