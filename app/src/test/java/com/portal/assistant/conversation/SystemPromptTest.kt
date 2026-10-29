package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the system prompt: role, built-in tool rules and the lines of the declared Lookups. */
class SystemPromptTest {

    @Test fun theClosingRuleIsPinnedAndPresentWhateverTheLookups() {
        val closingRule = "- Lookups: If you can't look it up, say 'I can't check that' — never guess.\n"
        for (lookups in listOf(emptySet(), setOf(Lookup.GOOGLE_SEARCH), setOf(Lookup.WEATHER, Lookup.WEB_SEARCH))) {
            assertTrue(lookups.toString(), SystemPrompt.build(lookups).contains(closingRule))
        }
    }

    @Test fun theOldSharedGoogleSearchLineIsGone() {
        // The single line that named Google Search to every model (and said "if you can't find it") is split up.
        for (lookups in listOf(emptySet(), setOf(Lookup.GOOGLE_SEARCH), setOf(Lookup.WEATHER, Lookup.WEB_SEARCH))) {
            assertFalse(lookups.toString(), SystemPrompt.build(lookups).contains("If you can't find it"))
        }
    }

    @Test fun theGoogleSearchLineAppearsOnlyWithGoogleSearch() {
        val line = "- Google Search: Use for real-time/current info (weather, news, stocks, sports, prices, hours, " +
            "recent events). Base answers on results.\n"
        assertTrue(SystemPrompt.build(setOf(Lookup.GOOGLE_SEARCH)).contains(line))
        assertFalse(SystemPrompt.build(emptySet()).contains("Google Search"))
        assertFalse(SystemPrompt.build(setOf(Lookup.WEATHER, Lookup.WEB_SEARCH)).contains("Google Search"))
    }

    @Test fun theWeatherAndWebSearchLinesNameTheirToolsOnlyWhenDeclared() {
        val both = SystemPrompt.build(setOf(Lookup.WEATHER, Lookup.WEB_SEARCH))
        assertTrue(both.contains("- Weather (portal.get_weather):"))
        assertTrue(both.contains("- Web search (portal.web_search):"))
        val none = SystemPrompt.build(setOf(Lookup.GOOGLE_SEARCH))
        assertFalse(none.contains("portal.get_weather"))
        assertFalse(none.contains("portal.web_search"))
    }

    @Test fun portalBuiltinsPresent() {
        val prompt = SystemPrompt.build(emptySet())
        assertTrue(prompt.contains("portal.set_volume"))
        assertTrue(prompt.contains("portal.get_time"))
    }

    @Test fun endingRuleNamesTheToolAndNoLongerClaimsConversationsEndAutomatically() {
        val prompt = SystemPrompt.build(emptySet())
        assertTrue(prompt.contains("portal.end_conversation"))
        assertFalse(prompt.contains("end automatically"))
    }
}
