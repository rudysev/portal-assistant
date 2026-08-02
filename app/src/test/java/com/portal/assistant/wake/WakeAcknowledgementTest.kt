package com.portal.assistant.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAcknowledgementTest {
    @Test
    fun phrasesAreShortDistinctAndNonBlank() {
        assertEquals(WakeAcknowledgement.phrases.size, WakeAcknowledgement.phrases.distinct().size)
        assertTrue(WakeAcknowledgement.phrases.all { it.isNotBlank() && it.length <= 24 })
    }

    @Test
    fun hiddenPromptRequiresOnlyTheSelectedPhrase() {
        val prompt = WakeAcknowledgement.promptFor("Yes?")
        assertTrue(prompt.contains("Say exactly: \"Yes?\""))
        assertTrue(prompt.contains("Say nothing else"))
        assertTrue(prompt.contains("wait for the user's request"))
    }
}
