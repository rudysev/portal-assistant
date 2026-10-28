package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the static system prompt. */
class SystemPromptTest {

    @Test fun googleSearchRulePresent() {
        val prompt = SystemPrompt.build()
        assertTrue(prompt.contains("Google Search"))
        assertTrue(prompt.contains("weather"))
        assertFalse(prompt.contains("web_search"))
        assertFalse(prompt.contains("get_weather"))
    }

    @Test fun googleSearchRuleSaysItCantCheckRatherThanGuess() {
        assertTrue(
            SystemPrompt.build().contains(
                "- Google Search: Use for real-time/current info (weather, news, stocks, sports, prices, hours, " +
                    "recent events). Base answers on results. If you can't find it, say 'I can't check that' — " +
                    "never guess.\n",
            ),
        )
    }

    @Test fun portalBuiltinsPresent() {
        val prompt = SystemPrompt.build()
        assertTrue(prompt.contains("portal.set_volume"))
        assertTrue(prompt.contains("portal.get_time"))
    }

    @Test fun endingRuleNamesTheToolAndNoLongerClaimsConversationsEndAutomatically() {
        val prompt = SystemPrompt.build()
        assertTrue(prompt.contains("portal.end_conversation"))
        assertFalse(prompt.contains("end automatically"))
    }
}
