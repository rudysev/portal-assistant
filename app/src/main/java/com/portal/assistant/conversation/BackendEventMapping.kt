package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendEvent

/**
 * What one [BackendEvent] means to the conversation: the reducer [Event] it feeds [reduce] and/or the
 * [Transcript.Event] it advances the transcript by. Pure — [AssistantEngine] reads [Phase] and the player's played
 * bytes on its handler, applies [Mapped.transcriptEvent] first, then dispatches [Mapped.conversationEvent].
 *
 * The one phase-dependent rule: an output transcript is [Event.ModelActivity] only while already SPEAKING — a belated
 * frame after listening restarted must not enter SPEAKING (and mute the mic) on its own.
 */
object BackendEventMapping {

    data class Mapped(val conversationEvent: Event?, val transcriptEvent: Transcript.Event?)

    fun map(event: BackendEvent, phase: Phase, playedBytes: Long): Mapped = when (event) {
        BackendEvent.Ready -> Mapped(Event.Ready, transcriptEvent = null)

        is BackendEvent.InputTranscript -> Mapped(Event.UserSpeaking, Transcript.Event.UserText(event.text))

        is BackendEvent.OutputTranscript -> Mapped(
            conversationEvent = Event.ModelActivity.takeIf { phase == Phase.SPEAKING },
            transcriptEvent = Transcript.Event.JarvisText(event.text, phase),
        )

        is BackendEvent.Audio ->
            Mapped(Event.PlaybackBusy, Transcript.Event.AudioReceived(event.pcm24k.size, playedBytes))

        BackendEvent.ModelGenerating -> Mapped(Event.ModelActivity, transcriptEvent = null)

        BackendEvent.TurnComplete -> Mapped(Event.TurnComplete, transcriptEvent = null)

        BackendEvent.Interrupted -> Mapped(Event.Interrupted, Transcript.Event.Interrupted(playedBytes))

        is BackendEvent.ToolCall -> Mapped(Event.ToolCallReceived(event.calls), transcriptEvent = null)

        is BackendEvent.ToolCancel -> Mapped(Event.ToolCancelled(event.ids), transcriptEvent = null)

        // The close that follows ends the conversation.
        is BackendEvent.ClosingSoon -> Mapped(conversationEvent = null, transcriptEvent = null)

        is BackendEvent.Failed, BackendEvent.Closed -> Mapped(Event.Disconnected, transcriptEvent = null)
    }
}
