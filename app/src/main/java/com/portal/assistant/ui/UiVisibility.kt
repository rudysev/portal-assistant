package com.portal.assistant.ui

/**
 * Whether the chat [MainActivity] is currently in the foreground. Set by the Activity's resume/pause. Its only
 * reader is the orange bar: [com.portal.assistant.conversation.AssistantEngine] gates the [RecordingOverlay] on it —
 * the bar shows only while the app is backgrounded (in-app UI replaces it when open). Foreground wake tracks being
 * on screen from the service's calls instead.
 *
 * A tiny standalone seam rather than a field on the transcript bus ([com.portal.assistant.conversation.ConversationHub]):
 * Activity-lifecycle state has nothing to do with the conversation transcript.
 */
object UiVisibility {
    @Volatile
    var inForeground: Boolean = false
}
