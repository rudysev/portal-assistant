package com.portal.assistant.ui

/**
 * Whether the chat [MainActivity] is currently in the foreground. Set by the Activity's resume/pause and
 * read by [com.portal.assistant.conversation.AssistantEngine] to gate the orange [RecordingOverlay] — the
 * bar shows only while the app is backgrounded (in-app UI replaces it when open).
 *
 * A tiny standalone seam rather than a field on the transcript bus ([com.portal.assistant.conversation.ConversationHub]):
 * Activity-lifecycle state has nothing to do with the conversation transcript.
 */
object UiVisibility {
    @Volatile
    var inForeground: Boolean = false
}
