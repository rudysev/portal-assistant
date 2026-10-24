package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendError
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.tools.AfterSpeech
import com.portal.assistant.conversation.tools.ToolRunner
import com.portal.assistant.system.AppPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AssistantEngine]'s ordering rules, driven through `start`/`stop` and its ports on the test thread:
 * a [SteppedClock] plays the engine's thread and drives both timers, a [FakeBackend] emits events synchronously (the
 * socket thread), a [FakeAudio] mic pushes frames synchronously (the capture thread), and a [RecordingSink] records
 * what the Hub and the orange bar were told. The reducer's own transitions are [ConversationStateTest]'s.
 */
class AssistantEngineTest {

    private val log = mutableListOf<String>() // connect, mic, tone, player, tools, bar and hub idle, in order
    private val clock = SteppedClock(nowMs = START_MS)
    private val audio = FakeAudio(log)
    private val sink = RecordingSink(log)
    private val reads = FakeOpeningReads()
    private val backends = FakeBackendFactory(log, deadAirStallMs = STALL_MS)
    private val toolSet = FakeToolSet(log)
    private var ends = 0

    private fun engine() = AssistantEngine(
        backendChoice = Backends.Choice(AppPrefs.VoiceBackendKind.GEMINI, backends, credential = "user-key"),
        clock = clock,
        audio = audio,
        sink = sink,
        openingReads = reads,
        toolRunners = { deliver -> ToolRunner(toolSet, AfterSpeech(), InlineExecutorService(), deliver) },
        onEnded = { ends++ },
    )

    /** The one backend the engine built. */
    private val backend get() = backends.built.single()

    /** Open a conversation for [trigger] and bring it to LISTENING: the backend's Ready, delivered. */
    private fun listening(trigger: Trigger = Trigger.WakeWord("jarvis")): AssistantEngine = engine().also {
        assertNull(it.start(trigger))
        emit(BackendEvent.Ready)
    }

    /** The backend emits [event] on its socket thread; the engine's thread then runs what it posted. */
    private fun emit(event: BackendEvent) {
        backend.emit(event)
        clock.runPosted()
    }

    /** The user is heard at [atMs]: one loud frame. */
    private fun speakAt(atMs: Long) {
        clock.advanceTo(atMs)
        audio.frame(FakeAudio.LOUD)
    }

    private val phase get() = sink.phases.last()

    // ---- fresh vs resume -------------------------------------------------------------------------------------------

    @Test fun aWakeStartsFreshEvenWithATranscriptOnScreen() {
        reads.onScreenTurns = listOf(Turn(Role.USER, "set a timer"), Turn(Role.MODEL, "Done."))
        listening(Trigger.WakeWord("jarvis"))
        emit(BackendEvent.InputTranscript("what's the weather"))

        assertEquals(listOf("fresh"), sink.starts)
        assertEquals(listOf("what's the weather"), sink.turnsShown.map { it.text })
    }

    @Test fun aMicTapResumesTheTranscriptOnScreenAndContinuesIt() {
        reads.onScreenTurns = listOf(Turn(Role.USER, "set a timer"), Turn(Role.MODEL, "Done."))
        listening(Trigger.MicTap)
        emit(BackendEvent.InputTranscript("and another"))

        assertEquals(listOf("resume"), sink.starts)
        assertEquals(listOf("set a timer", "Done.", "and another"), sink.turnsShown.map { it.text })
    }

    // ---- start order -----------------------------------------------------------------------------------------------

    @Test fun connectsBeforeOpeningTheMicAndStartsThePlayerLast() {
        engine().start(Trigger.WakeWord("jarvis"))

        assertEquals(listOf("connect", "mic open", "player start"), log)
    }

    @Test fun theListeningTonePlaysAtTheMicOpenWhenTheOpeningSaysSo() {
        reads.listeningToneEnabled = true
        engine().start(Trigger.WakeWord("jarvis"))

        assertEquals(listOf("connect", "mic open", "tone", "player start"), log)
    }

    @Test fun aSuggestionChipPlaysNoToneEvenWithTheSettingOn() {
        reads.listeningToneEnabled = true
        engine().start(Trigger.SuggestionChip("What's the weather?"))

        assertEquals(listOf("connect", "mic open", "player start"), log)
    }

    // ---- a suggestion chip's text ----------------------------------------------------------------------------------

    @Test fun aChipsTextIsSentOnceOnlyAfterReadyAndShownAsTheUserTurn() {
        engine().start(Trigger.SuggestionChip("What's the weather?"))
        backend.emit(BackendEvent.Ready)
        assertTrue(backend.sentTexts.isEmpty()) // not from the socket thread: once Ready is dispatched

        clock.runPosted()
        assertEquals(listOf("What's the weather?"), backend.sentTexts)
        assertEquals(listOf(Role.USER to "What's the weather?"), sink.turnsShown.map { it.role to it.text })

        emit(BackendEvent.Ready)
        assertEquals(1, backend.sentTexts.size)
    }

    // ---- the no-speech timer and its grace -------------------------------------------------------------------------

    @Test fun silenceEndsTheConversationAtTheNoSpeechDeadline() {
        listening()
        audio.frame(FakeAudio.QUIET) // heard, but not speech

        clock.advanceTo(START_MS + NO_SPEECH_MS - 1)
        assertEquals(0, ends)
        clock.advanceTo(START_MS + NO_SPEECH_MS)
        assertEquals(1, ends)
    }

    @Test fun speechHeardWithinTheRecentWindowExtendsTheDeadlineByOneGrace() {
        listening()
        val deadline = START_MS + NO_SPEECH_MS
        speakAt(deadline - NoSpeechGrace.RECENT_SPEECH_MS) // the window is inclusive

        clock.advanceTo(deadline)
        assertEquals(0, ends)
        assertEquals(listOf(deadline + NoSpeechGrace.GRACE_MS), clock.timersDueAt)
        clock.advanceTo(deadline + NoSpeechGrace.GRACE_MS) // nothing heard since: the next checkpoint ends it
        assertEquals(1, ends)
    }

    @Test fun speechOlderThanTheRecentWindowDoesNotExtend() {
        listening()
        val deadline = START_MS + NO_SPEECH_MS
        speakAt(deadline - NoSpeechGrace.RECENT_SPEECH_MS - 1)

        clock.advanceTo(deadline)
        assertEquals(1, ends)
    }

    @Test fun unconfirmedSpeechExtendsAtMostMaxGraces() {
        listening()
        var checkpoint = START_MS + NO_SPEECH_MS
        repeat(NoSpeechGrace.MAX_GRACES) {
            speakAt(checkpoint - 100)
            clock.advanceTo(checkpoint)
            assertEquals(0, ends)
            checkpoint += NoSpeechGrace.GRACE_MS
        }

        speakAt(checkpoint - 100) // still "speaking", but the server never confirmed any of it: noise
        clock.advanceTo(checkpoint)
        assertEquals(1, ends)
    }

    @Test fun aServerTranscriptResetsTheGraceBudget() {
        listening()
        var checkpoint = START_MS + NO_SPEECH_MS
        repeat(NoSpeechGrace.MAX_GRACES) {
            speakAt(checkpoint - 100)
            clock.advanceTo(checkpoint)
            checkpoint += NoSpeechGrace.GRACE_MS
        }

        speakAt(checkpoint - 100)
        emit(BackendEvent.InputTranscript("turn off the lights")) // re-arms in full and resets the budget
        checkpoint = clock.nowMs + NO_SPEECH_MS
        repeat(NoSpeechGrace.MAX_GRACES) {
            speakAt(checkpoint - 100)
            clock.advanceTo(checkpoint)
            assertEquals(0, ends)
            checkpoint += NoSpeechGrace.GRACE_MS
        }
        clock.advanceTo(checkpoint)
        assertEquals(1, ends)
    }

    // ---- the stall -------------------------------------------------------------------------------------------------

    @Test fun theStallFiresOnlyOnceTheAudioHasDrainedIntoDeadAir() {
        listening()
        emit(BackendEvent.ModelGenerating)
        backend.emit(BackendEvent.Audio(ByteArray(4_800)))
        assertEquals(1, audio.enqueued.size) // on the socket thread, before anything is posted
        clock.runPosted()

        clock.advanceBy(STALL_MS * 3) // a long answer still playing is never cut
        assertEquals(ConversationHub.UiPhase.SPEAKING, phase)
        assertEquals(0, audio.flushes)

        audio.drained(bytes = 4_800) // played out, yet the model never completed the turn
        clock.advanceBy(STALL_MS - 1)
        assertEquals(ConversationHub.UiPhase.SPEAKING, phase)
        clock.advanceBy(1)
        assertEquals(ConversationHub.UiPhase.LISTENING, phase)
        assertEquals(1, audio.flushes)
    }

    @Test fun aTurnThatCompletesAndDrainsLeavesNoStallArmed() {
        listening()
        emit(BackendEvent.ModelGenerating)
        emit(BackendEvent.Audio(ByteArray(4_800)))
        emit(BackendEvent.TurnComplete)
        audio.drained(bytes = 4_800)
        clock.runPosted()

        assertEquals(ConversationHub.UiPhase.LISTENING, phase)
        assertEquals(listOf(clock.nowMs + NO_SPEECH_MS), clock.timersDueAt) // only the no-speech timer
    }

    // ---- the disconnect notice -------------------------------------------------------------------------------------

    /** The notices posted when the backend reports [events] after the session got [ready] (or not). */
    private fun noticesFor(vararg events: BackendEvent, ready: Boolean): List<String> {
        if (ready) listening() else engine().start(Trigger.WakeWord("jarvis"))
        events.forEach(::emit)
        assertEquals(1, ends)
        return sink.notices
    }

    @Test fun aFailureThenCloseAfterReadyPostsOneConnectionLost() {
        val notices = noticesFor(BackendEvent.Failed(TRANSPORT_FAILURE), BackendEvent.Closed, ready = true)

        assertEquals(listOf(DisconnectNotice.CONNECTION_LOST), notices)
    }

    @Test fun aTransportFailureBeforeReadyIsCouldntReachNeverTheSocketText() {
        val notices = noticesFor(BackendEvent.Failed(TRANSPORT_FAILURE), BackendEvent.Closed, ready = false)

        assertEquals(listOf(DisconnectNotice.COULDNT_REACH), notices)
    }

    @Test fun aCloseWithNoErrorBeforeReadyIsCouldntReach() {
        assertEquals(listOf(DisconnectNotice.COULDNT_REACH), noticesFor(BackendEvent.Closed, ready = false))
    }

    @Test fun aServerMessageIsShownAsIs() {
        val notices = noticesFor(BackendEvent.Failed(REJECTED_KEY), BackendEvent.Closed, ready = false)

        assertEquals(listOf("API key not valid. Please pass a valid API key."), notices)
    }

    @Test fun aBlankServerMessageFallsBackToConnectionLostAfterReady() {
        val notices = noticesFor(BackendEvent.Failed(BackendError.Server("  ")), ready = true)

        assertEquals(listOf(DisconnectNotice.CONNECTION_LOST), notices)
    }

    @Test fun offlineWinsOverAServerMessage() {
        reads.online = false
        assertEquals(listOf(DisconnectNotice.OFFLINE), noticesFor(BackendEvent.Failed(REJECTED_KEY), ready = false))
    }

    @Test fun offlineWinsOverATransportFailure() {
        reads.online = false
        val notices = noticesFor(BackendEvent.Failed(TRANSPORT_FAILURE), ready = true)

        assertEquals(listOf(DisconnectNotice.OFFLINE), notices)
    }

    @Test fun theCloseFromOurOwnTeardownPostsNoNotice() {
        listening().stop()
        emit(BackendEvent.Closed)

        assertTrue(sink.notices.isEmpty())
    }

    @Test fun theCloseAfterANaturalEndPostsNoNotice() {
        listening()
        clock.advanceBy(NO_SPEECH_MS)
        emit(BackendEvent.Closed)

        assertTrue(sink.notices.isEmpty())
    }

    // ---- after the end ---------------------------------------------------------------------------------------------

    @Test fun aMessageQueuedBeforeStopDoesNotRepopulateTheHub() {
        val conversation = listening()
        backend.emit(BackendEvent.InputTranscript("hello")) // queued on the engine's thread, not yet run
        conversation.stop()
        val phases = sink.phases.toList()

        clock.runPosted()
        audio.drained()
        clock.advanceBy(NO_SPEECH_MS * 2)

        assertEquals(0, sink.turnWrites)
        assertEquals(phases, sink.phases)
        assertEquals(0, ends) // stop() never reports an end
    }

    @Test fun onEndedFiresOnceOnANaturalEnd() {
        val conversation = listening()
        clock.advanceBy(NO_SPEECH_MS)
        assertEquals(1, ends)

        emit(BackendEvent.Failed(TRANSPORT_FAILURE))
        emit(BackendEvent.Closed)
        conversation.stop()
        clock.advanceBy(NO_SPEECH_MS * 2)
        assertEquals(1, ends)
    }

    // ---- the orange bar --------------------------------------------------------------------------------------------

    @Test fun theBarShowsOnlyWhileListening() {
        engine().start(Trigger.WakeWord("jarvis"))
        audio.frame(FakeAudio.LOUD) // connecting: buffered, no bar
        assertTrue(sink.bar.isEmpty())

        emit(BackendEvent.Ready)
        audio.frame(FakeAudio.LOUD)
        assertEquals(listOf("show 0.5"), sink.bar)

        emit(BackendEvent.ModelGenerating)
        audio.frame(FakeAudio.LOUD) // muted while Jarvis speaks
        assertEquals(listOf("show 0.5", "hide"), sink.bar)
    }

    @Test fun theBarShowsOnlyWithTheAppInTheBackground() {
        val barCalls = mutableListOf<Pair<Boolean, Float>>()
        var inForeground = true
        val hubSink = HubSink(setRecording = { recording, level -> barCalls += recording to level }) { inForeground }

        hubSink.showRecordingBar(0.5f)
        inForeground = false
        hubSink.showRecordingBar(0.5f)
        hubSink.hideRecordingBar()

        assertEquals(listOf(false to 0.5f, true to 0.5f, false to 0f), barCalls)
    }

    // ---- tools -----------------------------------------------------------------------------------------------------

    @Test fun aToolCallRunsOnTheRunnerAndItsResultGoesBackToTheBackend() {
        listening()
        emit(BackendEvent.ToolCall(listOf(FunctionCall("call-1", "portal.get_time", JSONObject()))))

        assertEquals(listOf("portal.get_time"), toolSet.invoked)
        val result = backend.toolResponses.single().single()
        assertEquals("call-1", result.id)
        assertEquals("portal.get_time", result.response.getString("ok"))
    }

    // ---- teardown --------------------------------------------------------------------------------------------------

    @Test fun theEndReleasesEverythingInOrder() {
        listening()
        log.clear()
        clock.advanceBy(NO_SPEECH_MS)

        assertEquals(TEARDOWN, log)
        assertTrue(clock.timersDueAt.isEmpty())
    }

    @Test fun stopReleasesEverythingInOrderOnce() {
        val conversation = listening()
        emit(BackendEvent.ModelGenerating) // the stall is armed
        log.clear()
        conversation.stop()
        conversation.stop()

        assertEquals(TEARDOWN, log)
        assertTrue(clock.timersDueAt.isEmpty())
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val NO_SPEECH_MS = 5_000L
        const val STALL_MS = 8_000L

        val TRANSPORT_FAILURE = BackendError.Transport("Live connection failed: Unable to resolve host")
        val REJECTED_KEY = BackendError.Server("API key not valid. Please pass a valid API key.")

        val TEARDOWN = listOf("tools disposed", "mic close", "backend close", "player stop", "bar hide", "hub idle")
    }
}
