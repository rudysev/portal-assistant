package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the system prompt's built-in sections: the same skeleton whatever the Lookups. */
class SystemPromptTest {

    private val everyLookupSet = listOf(
        emptySet(),
        setOf(Lookup.GOOGLE_SEARCH),
        setOf(Lookup.WEATHER),
        setOf(Lookup.WEATHER, Lookup.WEB_SEARCH),
    )

    @Test fun theSectionsAreMarkdownHeadingsInAFixedOrder() {
        for (lookups in everyLookupSet) {
            val headings = SystemPrompt.build(lookups).lines().filter { it.startsWith("#") }
            assertEquals(
                lookups.toString(),
                listOf("# Role and Objective", "# Personality and Tone", "# Speaking", "# Tools"),
                headings,
            )
        }
    }

    @Test fun theSharedLookupRuleIsAlwaysUnderTools() {
        val rule = "if you can't, say \"I can't check that\" instead of guessing."
        for (lookups in everyLookupSet) {
            val prompt = SystemPrompt.build(lookups)
            assertTrue(lookups.toString(), prompt.indexOf(rule) > prompt.indexOf("# Tools"))
        }
    }

    @Test fun theGoogleSearchLineAppearsOnlyWithGoogleSearch() {
        val line = "- Google Search: use it for live information."
        assertTrue(SystemPrompt.build(setOf(Lookup.GOOGLE_SEARCH)).endsWith("\n$line"))
        for (lookups in everyLookupSet - setOf(setOf(Lookup.GOOGLE_SEARCH))) {
            assertFalse(lookups.toString(), SystemPrompt.build(lookups).contains("Google Search"))
        }
    }

    @Test fun noToolIsNamedSoEachRuleLivesOnlyInItsDeclaration() {
        for (lookups in everyLookupSet) {
            assertFalse(lookups.toString(), SystemPrompt.build(lookups).contains("portal."))
        }
    }

    @Test fun theRoleNamesJarvisAndSaysHowAConversationContinuesAndEnds() {
        val prompt = SystemPrompt.build(emptySet())
        assertTrue(prompt.contains("You are Jarvis"))
        assertTrue(prompt.contains("the user can simply keep talking (no wake word needed)"))
        assertTrue(prompt.contains("The conversation ends when they say they're done or go quiet."))
        // The old ban contradicted the final turn (the user asking to end is how it ends).
        assertFalse(prompt.contains("goodbye"))
    }

    @Test fun thePromptIsModelNeutral() {
        for (lookups in everyLookupSet) {
            assertFalse(lookups.toString(), SystemPrompt.build(lookups).contains("Gemini"))
        }
    }

    @Test fun itSaysItsWordsAreSpokenAndGivesAnAnswerLength() {
        val prompt = SystemPrompt.build(emptySet())
        assertTrue(prompt.contains("Your words are spoken aloud"))
        assertTrue(prompt.contains("usually two to three sentences"))
    }

    @Test fun noCapitalisedEmphasis() {
        val shouting = Regex("""\b[A-Z]{3,}\b""")
        for (lookups in everyLookupSet) {
            val shouted = shouting.findAll(SystemPrompt.build(lookups)).map { it.value }.toList()
            assertEquals(lookups.toString(), emptyList<String>(), shouted)
        }
    }
}
