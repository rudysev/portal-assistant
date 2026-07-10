package com.portal.assistant.conversation

/**
 * Pure policy for which user-facing banner to show when a conversation ends unexpectedly.
 *
 * Backend implementations surface specific errors via [VoiceBackend.Listener.onError] so the user can act
 * (missing credential, auth failure, unreachable host). When the device is offline, prefer the offline copy
 * over opaque transport failures ("unable to resolve host", etc.) — the actionable fix is Wi‑Fi, not the
 * socket message. [onClosed] without a prior error uses the generic connect/lost fallbacks.
 *
 * Android-free so it's unit-tested; [AssistantEngine] supplies [offline] and [connectedOk].
 */
object DisconnectNotice {

    const val OFFLINE = "You’re offline. Check your Wi-Fi connection and try again."
    const val CONNECTION_LOST = "Connection lost. Please try again."
    const val COULDNT_REACH = "Couldn’t reach Jarvis. Please try again."

    fun message(backendError: String?, offline: Boolean, connectedOk: Boolean): String {
        if (offline) return OFFLINE
        backendError?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (connectedOk) CONNECTION_LOST else COULDNT_REACH
    }
}
