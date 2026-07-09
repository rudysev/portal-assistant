package com.portal.assistant.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for session system-prompt assembly. */
class SessionPromptTest {

    @Test fun layersBuiltinExternalDeviceInOrder() {
        val out = SessionPrompt.build(
            builtinRules = "BUILTIN",
            externalToolLines = listOf("- Kasa. Tools: com.portal.kasa.set_plug."),
            deviceContextLines = listOf("Time: now (Z)."),
        )
        val builtinIdx = out.indexOf("BUILTIN")
        val externalIdx = out.indexOf(ExternalToolPrompt.HEADER)
        val deviceIdx = out.indexOf(SystemContext.HEADER)
        assertTrue(builtinIdx >= 0)
        assertTrue(externalIdx > builtinIdx)
        assertTrue(deviceIdx > externalIdx)
        assertTrue(out.contains("com.portal.kasa.set_plug"))
        assertTrue(out.contains("Time: now (Z)."))
    }

    @Test fun omitsExternalBlockWhenNoProviders() {
        val out = SessionPrompt.build(
            builtinRules = "BUILTIN",
            externalToolLines = emptyList(),
            deviceContextLines = listOf("Time: now (Z)."),
        )
        assertFalse(out.contains(ExternalToolPrompt.HEADER))
        assertTrue(out.contains(SystemContext.HEADER))
    }

    @Test fun resumeHistoryAppendedLast() {
        val out = SessionPrompt.build(
            builtinRules = "BUILTIN",
            externalToolLines = emptyList(),
            deviceContextLines = listOf("Time: now (Z)."),
            resumeTurns = listOf(Turn(Role.USER, "Hi"), Turn(Role.MODEL, "Hello")),
        )
        val deviceIdx = out.indexOf(SystemContext.HEADER)
        val historyIdx = out.indexOf("Here is the conversation so far")
        assertTrue(historyIdx > deviceIdx)
        assertTrue(out.contains("User: Hi"))
        assertTrue(out.contains("Assistant: Hello"))
    }
}
