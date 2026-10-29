package com.portal.assistant.ui

import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs.VoiceBackendKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the idle home suggests adding a Search key: only where one would add Web search. */
class SearchKeyNudgeTest {

    private val withGoogleSearch = GeminiModel.Model("with", "With", googleSearch = true)
    private val withoutGoogleSearch = GeminiModel.Model("without", "Without", googleSearch = false)

    @Test fun shownOnAModelWithoutGoogleSearchWithNoSearchKey() {
        assertTrue(showsSearchKeyNudge(VoiceBackendKind.GEMINI, withoutGoogleSearch, searchKeyPresent = false))
    }

    @Test fun hiddenOnceASearchKeyIsSet() {
        assertFalse(showsSearchKeyNudge(VoiceBackendKind.GEMINI, withoutGoogleSearch, searchKeyPresent = true))
    }

    @Test fun hiddenOnAModelWithGoogleSearch() {
        assertFalse(showsSearchKeyNudge(VoiceBackendKind.GEMINI, withGoogleSearch, searchKeyPresent = false))
    }

    @Test fun hiddenOnTheLocalBackend() {
        // Local gets no Jarvis lookups, so a Search key would change nothing there.
        assertFalse(showsSearchKeyNudge(VoiceBackendKind.LOCAL, withoutGoogleSearch, searchKeyPresent = false))
    }
}
