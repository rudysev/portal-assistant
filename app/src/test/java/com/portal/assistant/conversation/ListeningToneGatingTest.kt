package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for when the Settings switch previews the Listening tone (switched on, no conversation live).
 * Whether a conversation plays it is [ConversationOpeningTest]'s.
 */
class ListeningToneGatingTest {

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
