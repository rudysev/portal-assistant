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

    @Test fun portalBuiltinsPresent() {
        val prompt = SystemPrompt.build()
        assertTrue(prompt.contains("portal.set_volume"))
        assertTrue(prompt.contains("portal.get_time"))
    }
}
