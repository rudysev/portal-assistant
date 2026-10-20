package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for when the Listening tone plays: only for a voice query (wake word or mic tap) at the
 * conversation's start, with the setting on; and when the Settings switch previews it (switched on, no
 * conversation live).
 */
class ListeningToneGatingTest {

    @Test fun playsForAVoiceQueryAtConversationStartWithTheSettingOn() {
        assertTrue(ListeningTone.playsAtMicOpen(toneEnabled = true, voiceQuery = true, conversationStart = true))
    }

    @Test fun silentWhenTheSettingIsOff() {
        assertFalse(ListeningTone.playsAtMicOpen(toneEnabled = false, voiceQuery = true, conversationStart = true))
    }

    @Test fun silentForASuggestionChip() {
        // A chip's query is sent as text, so there is nothing to tell the user to say.
        assertFalse(ListeningTone.playsAtMicOpen(toneEnabled = true, voiceQuery = false, conversationStart = true))
    }

    @Test fun silentWhenTheMicReopensForAFollowUp() {
        // A back-and-forth conversation isn't punctuated by a tone before every follow-up.
        assertFalse(ListeningTone.playsAtMicOpen(toneEnabled = true, voiceQuery = true, conversationStart = false))
    }

    // --- The Settings switch's preview ---

    @Test fun switchingOnPreviewsTheTone() {
        assertTrue(ListeningTone.previewsOnSwitch(switchedOn = true, conversationLive = false))
    }

    @Test fun switchingOffIsSilent() {
        assertFalse(ListeningTone.previewsOnSwitch(switchedOn = false, conversationLive = false))
    }

    @Test fun noPreviewWhileAConversationIsLive() {
        // The mic is open: a preview would be recorded and sent to the voice backend (outside the engine's
        // suppression window, which only covers the conversation's own tone).
        assertFalse(ListeningTone.previewsOnSwitch(switchedOn = true, conversationLive = true))
    }
}
