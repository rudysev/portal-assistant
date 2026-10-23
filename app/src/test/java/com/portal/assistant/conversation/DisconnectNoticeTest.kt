package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendError
import org.junit.Assert.assertEquals
import org.junit.Test

class DisconnectNoticeTest {

    private val transportFailure = BackendError.Transport("Live connection failed: Unable to resolve host")
    private val rejectedKey = BackendError.Server("API key not valid. Please pass a valid API key.")

    @Test
    fun `offline prefers offline copy over a transport failure`() {
        assertEquals(
            DisconnectNotice.OFFLINE,
            DisconnectNotice.message(backendError = transportFailure, offline = true, connectedOk = false),
        )
    }

    @Test
    fun `offline prefers offline copy over a server message`() {
        assertEquals(
            DisconnectNotice.OFFLINE,
            DisconnectNotice.message(backendError = rejectedKey, offline = true, connectedOk = false),
        )
    }

    @Test
    fun `online shows a server message as is`() {
        assertEquals(
            "API key not valid. Please pass a valid API key.",
            DisconnectNotice.message(backendError = rejectedKey, offline = false, connectedOk = false),
        )
    }

    @Test
    fun `a transport failure before ready is couldnt reach, not the socket text`() {
        assertEquals(
            DisconnectNotice.COULDNT_REACH,
            DisconnectNotice.message(backendError = transportFailure, offline = false, connectedOk = false),
        )
    }

    @Test
    fun `a transport failure after ready is connection lost, not the socket text`() {
        assertEquals(
            DisconnectNotice.CONNECTION_LOST,
            DisconnectNotice.message(backendError = transportFailure, offline = false, connectedOk = true),
        )
    }

    @Test
    fun `a blank server message falls back to connection lost when session was ready`() {
        assertEquals(
            DisconnectNotice.CONNECTION_LOST,
            DisconnectNotice.message(backendError = BackendError.Server("  "), offline = false, connectedOk = true),
        )
    }

    @Test
    fun `a close with no error falls back to couldnt reach before ready`() {
        assertEquals(
            DisconnectNotice.COULDNT_REACH,
            DisconnectNotice.message(backendError = null, offline = false, connectedOk = false),
        )
    }
}
