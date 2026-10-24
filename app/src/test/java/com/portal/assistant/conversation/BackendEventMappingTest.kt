package com.portal.assistant.conversation

import com.portal.assistant.conversation.BackendEventMapping.Mapped
import com.portal.assistant.conversation.backend.BackendError
import com.portal.assistant.conversation.backend.BackendEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each [BackendEvent] means to the conversation: a reducer [Event] and/or a [Transcript.Event]. */
class BackendEventMappingTest {

    private val playedBytes = 1_234L

    private fun map(event: BackendEvent, phase: Phase) = BackendEventMapping.map(event, phase, playedBytes)

    @Test
    fun `a stray output transcript while listening reaches the transcript but never enters SPEAKING`() {
        assertEquals(
            Mapped(conversationEvent = null, transcriptEvent = Transcript.Event.JarvisText("hi", Phase.LISTENING)),
            map(BackendEvent.OutputTranscript("hi"), Phase.LISTENING),
        )
    }

    @Test
    fun `an output transcript only counts as model activity while already SPEAKING`() {
        val expected = mapOf(
            Phase.CONNECTING to null,
            Phase.LISTENING to null,
            Phase.SPEAKING to Event.ModelActivity,
            Phase.ENDED to null,
        )
        for (phase in Phase.entries) {
            assertEquals(
                "in $phase",
                Mapped(expected.getValue(phase), Transcript.Event.JarvisText("hi", phase)),
                map(BackendEvent.OutputTranscript("hi"), phase),
            )
        }
    }

    private val call = FunctionCall("a", "portal.set_timer", JSONObject())

    /** Every other event, and what it means in any phase. */
    private val phaseIndependent: List<Pair<BackendEvent, Mapped>> = listOf(
        BackendEvent.Ready to Mapped(Event.Ready, transcriptEvent = null),
        BackendEvent.InputTranscript("set a") to Mapped(Event.UserSpeaking, Transcript.Event.UserText("set a")),
        BackendEvent.Audio(ByteArray(480)) to
            Mapped(Event.PlaybackBusy, Transcript.Event.AudioReceived(bytes = 480, playedBytes = 1_234L)),
        BackendEvent.ModelGenerating to Mapped(Event.ModelActivity, transcriptEvent = null),
        BackendEvent.TurnComplete to Mapped(Event.TurnComplete, transcriptEvent = null),
        BackendEvent.Interrupted to Mapped(Event.Interrupted, Transcript.Event.Interrupted(playedBytes = 1_234L)),
        BackendEvent.ToolCall(listOf(call)) to Mapped(Event.ToolCallReceived(listOf(call)), transcriptEvent = null),
        BackendEvent.ToolCancel(listOf("a")) to Mapped(Event.ToolCancelled(listOf("a")), transcriptEvent = null),
        BackendEvent.ClosingSoon(5_000) to Mapped(conversationEvent = null, transcriptEvent = null),
        BackendEvent.Failed(BackendError.Transport("reset")) to Mapped(Event.Disconnected, transcriptEvent = null),
        BackendEvent.Failed(BackendError.Server("bad key")) to Mapped(Event.Disconnected, transcriptEvent = null),
        BackendEvent.Closed to Mapped(Event.Disconnected, transcriptEvent = null),
    )

    @Test
    fun `every other backend event means the same in every phase`() {
        for (phase in Phase.entries) {
            for ((event, expected) in phaseIndependent) {
                assertEquals("$event in $phase", expected, map(event, phase))
            }
        }
    }
}
