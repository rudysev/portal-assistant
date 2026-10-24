package com.portal.assistant.conversation.backend

/**
 * Why a voice backend's session failed, as [BackendEvent.Failed] reports it. The two kinds get different
 * user-facing notices ([com.portal.assistant.conversation.DisconnectNotice]):
 *  - [Transport]: the socket broke or never opened. Its [Transport.detail] is raw socket text ("Live connection
 *    failed: timeout") — for the log, never shown; the user sees the generic connect/lost copy.
 *  - [Server]: the server told us why, in words meant for a person (the local host's error frame, or a close
 *    reason such as Gemini rejecting the API key) — shown as is.
 */
sealed interface BackendError {
    data class Transport(val detail: String) : BackendError
    data class Server(val message: String) : BackendError
}
