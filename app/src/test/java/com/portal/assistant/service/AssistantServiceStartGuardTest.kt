package com.portal.assistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [AssistantService] start gates: no credential → no engine construction; a start-aborted instance
 * must not occupy the live slot; the wake earcon fires only for a hands-free trigger.
 */
class AssistantServiceStartGuardTest {

    @Test
    fun `wake chime plays only for a hands-free trigger`() {
        assertTrue(AssistantService.shouldPlayWakeChime(AssistantService.wakeSource("jarvis"), enabledInSettings = true))
        assertFalse(AssistantService.shouldPlayWakeChime(AssistantService.SOURCE_TAP, enabledInSettings = true))
        assertFalse(AssistantService.shouldPlayWakeChime(AssistantService.SOURCE_CHIP, enabledInSettings = true))
        assertFalse(AssistantService.shouldPlayWakeChime("?", enabledInSettings = true))
        // The bare prefix isn't a wake source — both wake paths carry a detector id.
        assertFalse(AssistantService.shouldPlayWakeChime(AssistantService.SOURCE_WAKE_PREFIX, enabledInSettings = true))
    }

    @Test
    fun `wake chime setting off silences every source`() {
        listOf(
            AssistantService.wakeSource("jarvis"),
            AssistantService.SOURCE_TAP,
            AssistantService.SOURCE_CHIP,
        ).forEach { source ->
            assertFalse(source, AssistantService.shouldPlayWakeChime(source, enabledInSettings = false))
        }
    }

    @Test
    fun `canOpenConversation rejects missing credential`() {
        assertFalse(AssistantService.canOpenConversation(credentialMissing = true))
        assertTrue(AssistantService.canOpenConversation(credentialMissing = false))
    }

    @Test
    fun `retainIfStarted keeps candidate only when start succeeded`() {
        assertNull(AssistantService.retainIfStarted(started = false, candidate = "engine"))
        assertEquals("engine", AssistantService.retainIfStarted(started = true, candidate = "engine"))
    }

    @Test
    fun `retainIfStarted models the fixed slot assign path`() {
        var engine: String? = null
        fun tryStart(started: Boolean) {
            if (engine != null) return
            val candidate = "engine"
            engine = AssistantService.retainIfStarted(started, candidate)
        }
        tryStart(started = false)
        assertNull(engine)
        tryStart(started = true)
        assertEquals("engine", engine)
    }

    @Test
    fun `failed start rolls back running so retry can proceed`() {
        var engine: String? = null
        var running = false
        fun tryStart(started: Boolean) {
            if (engine != null) return
            running = true
            engine = AssistantService.retainIfStarted(started, "engine")
            if (engine == null) running = false
        }
        tryStart(started = false)
        assertNull(engine)
        assertFalse(running)
        tryStart(started = true)
        assertEquals("engine", engine)
        assertTrue(running)
    }

    @Test
    fun `assigning after sync onEnded on aborted start blocks retry`() {
        var engine: String? = null
        fun tryStartBuggy(started: Boolean) {
            if (engine != null) return
            val candidate = "engine"
            if (!started) engine = null // onEnded → returnToStandby
            engine = candidate // pre-fix: `.also { start() }` always assigned
        }
        tryStartBuggy(started = false)
        assertEquals("engine", engine)
        var retryReached = false
        if (engine == null) retryReached = true
        assertFalse(retryReached)
    }
}
