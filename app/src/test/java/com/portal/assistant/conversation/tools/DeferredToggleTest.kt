package com.portal.assistant.conversation.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the DeferredToggle (report the intended state now, apply it at turn end). */
class DeferredToggleTest {

    private val afterSpeech = AfterSpeech()
    private val toggle = DeferredToggle(afterSpeech)
    private var applied = 0

    @Test fun appliesOnlyAtTurnEndAndReportsPendingUntilThen() {
        toggle.schedule { applied++ }
        assertTrue(toggle.isPending)
        assertEquals(0, applied)

        afterSpeech.fire()
        assertEquals(1, applied)
        assertFalse(toggle.isPending)
    }

    @Test fun canBeScheduledAgainAfterItApplied() {
        toggle.schedule { applied++ }
        afterSpeech.fire()

        toggle.schedule { applied++ }
        assertTrue(toggle.isPending)
        afterSpeech.fire()
        assertEquals(2, applied)
    }

    @Test fun cancelBeforeTurnEndDropsTheEffect() {
        toggle.schedule { applied++ }
        toggle.cancel()
        assertFalse(toggle.isPending)

        afterSpeech.fire()
        assertEquals(0, applied)
    }

    @Test fun disposeDropsAPendingEffectSoItCantFireIntoTheNextConversation() {
        toggle.schedule { applied++ }
        toggle.dispose()
        assertFalse(toggle.isPending)

        afterSpeech.fire()
        assertEquals(0, applied)
    }
}
