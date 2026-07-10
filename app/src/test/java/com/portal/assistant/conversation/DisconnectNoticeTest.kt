package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectNoticeTest {

    @Test
    fun `offline prefers offline copy over backend transport error`() {
        assertEquals(
            DisconnectNotice.OFFLINE,
            DisconnectNotice.message(
                backendError = "Live connection failed: Unable to resolve host",
                offline = true,
                connectedOk = false,
            ),
        )
    }

    @Test
    fun `online surfaces backend error`() {
        assertEquals(
            "No Gemini API key is set. Add one in Settings → API key.",
            DisconnectNotice.message(
                backendError = "No Gemini API key is set. Add one in Settings → API key.",
                offline = false,
                connectedOk = false,
            ),
        )
    }

    @Test
    fun `blank backend error falls back to connection lost when session was ready`() {
        assertEquals(
            DisconnectNotice.CONNECTION_LOST,
            DisconnectNotice.message(backendError = "  ", offline = false, connectedOk = true),
        )
    }

    @Test
    fun `null backend error falls back to couldnt reach before ready`() {
        assertEquals(
            DisconnectNotice.COULDNT_REACH,
            DisconnectNotice.message(backendError = null, offline = false, connectedOk = false),
        )
    }
}
