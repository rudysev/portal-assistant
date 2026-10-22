package com.portal.assistant.conversation

/**
 * What starts a conversation — exactly one of the wake word, a mic tap, or a suggestion chip. Everything else
 * about how the conversation opens (resume or fresh, voice query or not, Listening tone or not) follows from
 * which one it was. Typed end to end; the only untyped hop is the Intent into the service, encoded and decoded
 * side by side in [AssistantService][com.portal.assistant.service.AssistantService]'s companion.
 */
sealed interface Trigger {

    /** How the trigger reads in debug.txt, e.g. `wake:jarvis`. */
    val logLabel: String

    /** "hey jarvis" — from portal-wake's hand-off (gen1) or the foreground detector (gen2). [wakeId] is for the log. */
    data class WakeWord(val wakeId: String) : Trigger {
        override val logLabel: String get() = "wake:$wakeId"
    }

    /** The mic tapped in the app. */
    data object MicTap : Trigger {
        override val logLabel: String get() = "tap"
    }

    /** A suggested question tapped on the idle home; its [text] is sent as the first turn. */
    data class SuggestionChip(val text: String) : Trigger {
        override val logLabel: String get() = "chip"
    }
}
