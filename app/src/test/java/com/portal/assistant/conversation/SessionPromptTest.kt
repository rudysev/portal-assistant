package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM tests for session system-prompt assembly. */
class SessionPromptTest {

    private val kasa = "- Kasa. Tools: com.portal.kasa.set_plug."
    private val time = "Time at conversation start: now (Z)."

    private fun headings(prompt: String) = prompt.lines().filter { it.startsWith("#") }

    @Test fun theBuiltinSectionsThenDeviceContext() {
        val out = SessionPrompt.build(
            lookups = setOf(Lookup.GOOGLE_SEARCH),
            externalToolLines = listOf(kasa),
            deviceContextLines = listOf(time),
        )
        assertEquals(
            SystemPrompt.build(setOf(Lookup.GOOGLE_SEARCH), listOf(kasa)) + "\n\n# Device Context\n- $time",
            out,
        )
        assertEquals(
            listOf("# Role and Objective", "# Personality and Tone", "# Speaking", "# Tools", "# Device Context"),
            headings(out),
        )
    }

    @Test fun resumeHistoryIsTheLastSection() {
        val out = SessionPrompt.build(
            lookups = emptySet(),
            externalToolLines = listOf(kasa),
            deviceContextLines = listOf(time),
            resumeTurns = listOf(Turn(Role.USER, "Hi"), Turn(Role.MODEL, "Hello")),
        )
        assertEquals("# Conversation So Far", headings(out).last())
        assertEquals(
            "\n\n# Conversation So Far\nContinue from these earlier turns.\nUser: Hi\nAssistant: Hello",
            out.substringAfter("- $time"),
        )
    }
}
