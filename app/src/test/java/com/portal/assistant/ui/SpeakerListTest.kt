package com.portal.assistant.ui

import com.portal.assistant.gemini.GeminiSpeaker
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for where the Voice dialog's Speaker list opens. */
class SpeakerListTest {

    private fun speaker(name: String) = GeminiSpeaker.find(name)!!

    @Test fun serverDefaultOpensAtTheTop() {
        assertEquals(0, speakerListStartIndex(null))
    }

    @Test fun aChosenSpeakerFarDownTheListOpensWithItOnScreenWithRowsAboveForContext() {
        // Charon is catalog index 8, Zephyr 28 — each opens three rows above itself.
        assertEquals(5, speakerListStartIndex(speaker("Charon")))
        assertEquals(25, speakerListStartIndex(speaker("Zephyr")))
    }

    @Test fun aChosenSpeakerNearTheTopOpensAtTheTop() {
        assertEquals(0, speakerListStartIndex(speaker("Achernar")))
        assertEquals(0, speakerListStartIndex(speaker("Algieba"))) // index 3 — already on screen
    }
}
