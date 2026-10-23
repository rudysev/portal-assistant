package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendError

/**
 * Pure policy for which user-facing banner to show when a conversation ends unexpectedly.
 *
 * Offline wins over everything — the actionable fix is Wi‑Fi. Otherwise a [BackendError.Server] message is shown
 * as is (the server said why, e.g. a rejected API key); a [BackendError.Transport] failure, or a close with no
 * error, gets the generic copy — "Connection lost" if the session had been ready, else "Couldn't reach Jarvis" —
 * never the raw socket text.
 *
 * Android-free so it's unit-tested; [AssistantEngine] supplies [offline] and [connectedOk].
 */
object DisconnectNotice {

    const val OFFLINE = "You’re offline. Check your Wi-Fi connection and try again."
    const val CONNECTION_LOST = "Connection lost. Please try again."
    const val COULDNT_REACH = "Couldn’t reach Jarvis. Please try again."

    fun message(backendError: BackendError?, offline: Boolean, connectedOk: Boolean): String {
        if (offline) return OFFLINE
        val serverMessage = when (backendError) {
            is BackendError.Server -> backendError.message.trim().takeIf { it.isNotEmpty() }
            is BackendError.Transport, null -> null
        }
        return serverMessage ?: if (connectedOk) CONNECTION_LOST else COULDNT_REACH
    }
}
