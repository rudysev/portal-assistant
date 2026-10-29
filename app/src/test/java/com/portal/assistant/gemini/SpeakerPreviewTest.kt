package com.portal.assistant.gemini

import com.portal.assistant.conversation.backend.Frame
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.assistant.conversation.backend.WebSocketVoiceBackend
import com.portal.assistant.gemini.SpeakerPreview.Action
import com.portal.assistant.gemini.SpeakerPreview.Event
import com.portal.assistant.gemini.SpeakerPreview.RowStatus
import com.portal.assistant.gemini.SpeakerPreview.State
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests of the Speaker preview state machine — the orderings that are fiddly to reproduce on device. */
class SpeakerPreviewTest {

    private val charon = GeminiSpeaker.find("Charon")!!

    /** A dialog opened with a key and no live conversation. */
    private val ready = State(hasKey = true)

    @Test fun tappingARowStartsLoadingItsPreview() {
        val d = SpeakerPreview.reduce(ready, Event.Tap(charon))
        assertEquals(RowStatus.LOADING, d.state.rowStatus(charon))
        assertEquals(listOf(Action.Connect(id = 1, speaker = charon)), d.actions)
    }

    /** Run [events] from [start], collecting every action. */
    private fun reduceAll(start: State, vararg events: Event): SpeakerPreview.Decision {
        var state = start
        val actions = mutableListOf<Action>()
        for (e in events) {
            val d = SpeakerPreview.reduce(state, e)
            state = d.state
            actions += d.actions
        }
        return SpeakerPreview.Decision(state, actions)
    }

    @Test fun aReadySessionIsAskedForTheIntroduction() {
        val d = reduceAll(ready, Event.Tap(charon), Event.Ready(id = 1))
        assertEquals(Action.SendIntro(id = 1), d.actions.last())
        assertEquals(RowStatus.LOADING, d.state.rowStatus(charon))
    }

    @Test fun firstAudioTurnsTheRowToPlaying() {
        val d = reduceAll(ready, Event.Tap(charon), Event.Ready(1), Event.Audio(1))
        assertEquals(RowStatus.PLAYING, d.state.rowStatus(charon))
        assertEquals(RowStatus.NONE, d.state.rowStatus(null)) // only the tapped row shows it
    }

    /** Preview 1 of [charon], audio flowing. */
    private val playing = reduceAll(ready, Event.Tap(charon), Event.Ready(1), Event.Audio(1)).state

    @Test fun turnCompleteClosesTheSocketButLetsTheAudioFinish() {
        val d = SpeakerPreview.reduce(playing, Event.TurnComplete(1))
        assertEquals(listOf(Action.CloseSession(1)), d.actions)
        assertEquals(RowStatus.PLAYING, d.state.rowStatus(charon))
    }

    @Test fun drainAfterTurnCompleteEndsThePreview() {
        val d = reduceAll(playing, Event.TurnComplete(1), Event.PlaybackDrained(1))
        assertEquals(Action.Stop(1), d.actions.last())
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
    }

    @Test fun turnCompleteAfterTheAudioAlreadyDrainedEndsThePreview() {
        // Short answer: every chunk played out before turnComplete arrived — no further drain will fire.
        val d = reduceAll(playing, Event.PlaybackDrained(1), Event.TurnComplete(1))
        assertEquals(listOf(Action.KeepClip(1, charon), Action.Stop(1)), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
    }

    @Test fun aMidStreamUnderrunDoesNotEndThePreview() {
        val d = reduceAll(playing, Event.PlaybackDrained(1), Event.Audio(1), Event.TurnComplete(1))
        assertEquals(listOf(Action.CloseSession(1)), d.actions)
        assertEquals(RowStatus.PLAYING, d.state.rowStatus(charon))
    }

    @Test fun aConnectionErrorMarksThatRowFailed() {
        val d = reduceAll(ready, Event.Tap(charon), Event.Disconnected(1))
        assertEquals(Action.Stop(1), d.actions.last())
        assertEquals(RowStatus.FAILED, d.state.rowStatus(charon))
        assertEquals(RowStatus.NONE, d.state.rowStatus(null))
    }

    @Test fun aDropMidPlaybackMarksTheRowFailed() {
        val d = SpeakerPreview.reduce(playing, Event.Disconnected(1))
        assertEquals(listOf(Action.Stop(1)), d.actions)
        assertEquals(RowStatus.FAILED, d.state.rowStatus(charon))
    }

    @Test fun ourOwnCloseAfterTurnCompleteIsNotAFailure() {
        val d = reduceAll(playing, Event.TurnComplete(1), Event.Disconnected(1))
        assertEquals(listOf(Action.CloseSession(1)), d.actions)
        assertEquals(RowStatus.PLAYING, d.state.rowStatus(charon))
    }

    @Test fun anAnswerWithNoAudioMarksTheRowFailed() {
        val d = reduceAll(ready, Event.Tap(charon), Event.Ready(1), Event.TurnComplete(1))
        assertEquals(Action.Stop(1), d.actions.last())
        assertEquals(RowStatus.FAILED, d.state.rowStatus(charon))
    }

    @Test fun tappingAnotherRowStopsTheFirstPreviewBeforeStartingTheNext() {
        val d = SpeakerPreview.reduce(playing, Event.Tap(null))
        assertEquals(listOf(Action.Stop(1), Action.Connect(id = 2, speaker = null)), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
        assertEquals(RowStatus.LOADING, d.state.rowStatus(null)) // the Default row previews Server default
    }

    @Test fun aStoppedPreviewsLateCallbacksCannotTouchItsSuccessor() {
        val next = SpeakerPreview.reduce(playing, Event.Tap(null)).state
        for (late in listOf(
            Event.Ready(1),
            Event.Audio(1),
            Event.TurnComplete(1),
            Event.PlaybackDrained(1),
            Event.Disconnected(1),
        )) {
            val d = SpeakerPreview.reduce(next, late)
            assertEquals(next, d.state)
            assertEquals(emptyList<Action>(), d.actions)
        }
    }

    @Test fun tappingAfterAFailureRetries() {
        val failed = reduceAll(ready, Event.Tap(charon), Event.Disconnected(1)).state
        val d = SpeakerPreview.reduce(failed, Event.Tap(charon))
        assertEquals(listOf(Action.Connect(id = 2, speaker = charon)), d.actions) // nothing left to stop
        assertEquals(RowStatus.LOADING, d.state.rowStatus(charon))
    }

    @Test fun dismissingTheDialogStopsThePreview() {
        val d = SpeakerPreview.reduce(playing, Event.Dismissed)
        assertEquals(listOf(Action.Stop(1)), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
    }

    @Test fun dismissingWithNothingPlayingDoesNothing() {
        assertEquals(emptyList<Action>(), SpeakerPreview.reduce(ready, Event.Dismissed).actions)
    }

    @Test fun withoutAKeyATapNeverConnects() {
        val noKey = State(hasKey = false)
        val d = SpeakerPreview.reduce(noKey, Event.Tap(charon))
        assertEquals(emptyList<Action>(), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
        assertEquals(SpeakerPreview.Hint.NO_KEY, d.state.hint)
        assertEquals("Add your Gemini API key to hear previews.", SpeakerPreview.Hint.NO_KEY.text)
    }

    @Test fun aReadyDialogShowsNoHint() {
        assertNull(ready.hint)
    }

    @Test fun aConversationStartingMidPreviewStopsItImmediately() {
        val d = SpeakerPreview.reduce(playing, Event.ConversationLive(true))
        assertEquals(listOf(Action.Stop(1)), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
        assertEquals(SpeakerPreview.Hint.PAUSED, d.state.hint)
        assertEquals("Previews are paused while Jarvis is talking.", SpeakerPreview.Hint.PAUSED.text)
    }

    @Test fun whileAConversationIsLiveATapOnlySelects() {
        val paused = SpeakerPreview.reduce(ready, Event.ConversationLive(true)).state
        val d = SpeakerPreview.reduce(paused, Event.Tap(charon))
        assertEquals(emptyList<Action>(), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
    }

    @Test fun previewsResumeWhenTheConversationEnds() {
        val resumed = reduceAll(ready, Event.ConversationLive(true), Event.ConversationLive(false)).state
        assertNull(resumed.hint)
        val d = SpeakerPreview.reduce(resumed, Event.Tap(charon))
        assertEquals(listOf(Action.Connect(id = 1, speaker = charon)), d.actions)
    }

    @Test fun aConversationStartingClearsAFailedRow() {
        val failed = reduceAll(ready, Event.Tap(charon), Event.Disconnected(1)).state
        val d = SpeakerPreview.reduce(failed, Event.ConversationLive(true))
        assertEquals(emptyList<Action>(), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
    }

    @Test fun anUnchangedIdleConversationLeavesAPreviewAlone() {
        // The dialog reports the hub's phase on open and on every change — a repeat "not live" is no news.
        val d = SpeakerPreview.reduce(playing, Event.ConversationLive(false))
        assertEquals(playing, d.state)
        assertEquals(emptyList<Action>(), d.actions)
    }

    // ---- replaying a completed preview -------------------------------------------------------------------

    /** Preview 1 of [charon] played to the end. */
    private val played = reduceAll(playing, Event.TurnComplete(1), Event.PlaybackDrained(1))

    @Test fun aCompletedPreviewIsKeptForReplay() {
        assertEquals(listOf(Action.CloseSession(1), Action.KeepClip(1, charon), Action.Stop(1)), played.actions)
    }

    @Test fun reTappingACompletedPreviewReplaysItWithoutASession() {
        val d = SpeakerPreview.reduce(played.state, Event.Tap(charon))
        assertEquals(listOf(Action.Replay(id = 2, speaker = charon)), d.actions)
        assertEquals(RowStatus.PLAYING, d.state.rowStatus(charon))
    }

    /** [charon]'s kept clip replaying as preview 2. */
    private val replaying = SpeakerPreview.reduce(played.state, Event.Tap(charon)).state

    @Test fun aReplayEndsWhenItsAudioDrains() {
        val d = SpeakerPreview.reduce(replaying, Event.PlaybackDrained(2))
        assertEquals(listOf(Action.Stop(2)), d.actions) // already kept — nothing to keep again
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
        assertEquals(
            listOf(Action.Replay(id = 3, speaker = charon)),
            SpeakerPreview.reduce(d.state, Event.Tap(charon)).actions,
        )
    }

    @Test fun aPreviewCutShortIsNotKept() {
        // Answer in, audio still playing — then another row's tap, a dismissal, a conversation, or a drop.
        val almost = SpeakerPreview.reduce(playing, Event.TurnComplete(1)).state
        for (cut in listOf(Event.Tap(null), Event.Dismissed, Event.ConversationLive(true))) {
            val after = reduceAll(almost, cut, Event.ConversationLive(false), Event.Tap(charon))
            assertFalse(after.actions.any { it is Action.KeepClip })
            assertEquals(Action.Connect(id = after.state.lastId, speaker = charon), after.actions.last())
        }
        val dropped = reduceAll(playing, Event.Disconnected(1), Event.Tap(charon))
        assertEquals(Action.Connect(id = 2, speaker = charon), dropped.actions.last())
    }

    @Test fun tappingAnotherRowStopsAReplay() {
        val d = SpeakerPreview.reduce(replaying, Event.Tap(null))
        assertEquals(listOf(Action.Stop(2), Action.Connect(id = 3, speaker = null)), d.actions)
        assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
    }

    @Test fun dismissingOrAConversationStopsAReplay() {
        for (stop in listOf(Event.Dismissed, Event.ConversationLive(true))) {
            val d = SpeakerPreview.reduce(replaying, stop)
            assertEquals(listOf(Action.Stop(2)), d.actions)
            assertEquals(RowStatus.NONE, d.state.rowStatus(charon))
        }
    }

    @Test fun whileAConversationIsLiveAKeptPreviewDoesNotReplay() {
        val paused = SpeakerPreview.reduce(played.state, Event.ConversationLive(true)).state
        assertEquals(emptyList<Action>(), SpeakerPreview.reduce(paused, Event.Tap(charon)).actions)
        val resumed = SpeakerPreview.reduce(paused, Event.ConversationLive(false)).state
        assertEquals(
            listOf(Action.Replay(id = 2, speaker = charon)),
            SpeakerPreview.reduce(resumed, Event.Tap(charon)).actions,
        )
    }

    @Test fun theDefaultRowKeepsItsOwnClip() {
        val both = reduceAll(
            played.state,
            Event.Tap(null),
            Event.Ready(2),
            Event.Audio(2),
            Event.TurnComplete(2),
            Event.PlaybackDrained(2),
        )
        assertEquals(Action.KeepClip(2, null), both.actions[both.actions.size - 2])
        assertEquals(
            listOf(Action.Replay(id = 3, speaker = null)),
            SpeakerPreview.reduce(both.state, Event.Tap(null)).actions,
        )
        assertEquals(
            listOf(Action.Replay(id = 3, speaker = charon)),
            SpeakerPreview.reduce(both.state, Event.Tap(charon)).actions,
        )
    }

    // ---- the preview session --------------------------------------------------------------------------

    private val silent = VoiceBackend.Listener { }

    /** The preview's session config, mapped the way the Gemini factory maps a conversation's. */
    private fun configOf(speaker: GeminiSpeaker.Speaker?) =
        SpeakerPreview.sessionConfig(apiKey = "key", model = "gemini-live-x", speaker = speaker)

    private fun setupOf(speaker: GeminiSpeaker.Speaker?): JSONObject =
        JSONObject((GeminiBackend.wireFormat(configOf(speaker)).setup() as Frame.Text).text).getJSONObject("setup")

    @Test fun previewSessionRunsOnTheSharedTransport() {
        assertTrue(GeminiBackend.Factory.create(configOf(charon), silent) is WebSocketVoiceBackend)
    }

    @Test fun previewSessionConnectsWithTheApiKey() {
        assertEquals("${GeminiBackend.DEFAULT_ENDPOINT}?key=key", GeminiBackend.connectAddress(configOf(charon)))
    }

    @Test fun previewSessionUsesTheSelectedModelAndTheCandidateSpeaker() {
        val setup = setupOf(charon)
        assertEquals("models/gemini-live-x", setup.getString("model"))
        assertEquals(
            "Charon",
            setup.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
    }

    @Test fun defaultRowPreviewsTheServerDefault() {
        assertFalse(setupOf(null).getJSONObject("generationConfig").has("speechConfig"))
    }

    @Test fun previewSessionDeclaresNoToolsAndIntroducesJarvis() {
        val setup = setupOf(charon)
        // No functions and no Google Search: a preview has no Lookups.
        assertFalse(setup.has("tools"))
        val instruction = setup.getJSONObject(
            "systemInstruction",
        ).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction.contains("Jarvis"))
    }
}
