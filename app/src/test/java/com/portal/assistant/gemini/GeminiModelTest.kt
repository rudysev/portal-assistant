package com.portal.assistant.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Model catalog: what Settings offers, each Model's name, and which ones offer Google Search. */
class GeminiModelTest {

    @Test fun catalogOffersGemini25AsTheDefaultAndGemini38() {
        assertEquals(
            listOf("gemini-2.5-flash-native-audio-latest", "gemini-3.8-live"),
            GeminiModel.AVAILABLE.map { it.id },
        )
        assertEquals("gemini-2.5-flash-native-audio-latest", GeminiModel.DEFAULT.id)
    }

    @Test fun eachModelIsShownByItsName() {
        assertEquals(listOf("Gemini 2.5", "Gemini 3.8"), GeminiModel.AVAILABLE.map { it.name })
    }

    @Test fun onlyGemini25OffersGoogleSearch() {
        assertTrue(GeminiModel.of("gemini-2.5-flash-native-audio-latest").googleSearch)
        assertFalse(GeminiModel.of("gemini-3.8-live").googleSearch)
    }

    @Test fun anIdNoLongerOfferedIsNotFoundAndFallsBackToGemini25() {
        assertNull(GeminiModel.find("gemini-1.0-retired"))
        assertEquals(GeminiModel.DEFAULT, GeminiModel.of("gemini-1.0-retired"))
    }
}
