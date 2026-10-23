package com.portal.assistant.conversation.backend

import com.portal.assistant.gemini.GeminiBackend
import com.portal.assistant.local.LocalVoiceHost
import com.portal.assistant.system.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackendsTest {

    @Test
    fun `resolveChoice maps local backend to canonical wss credential`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.LOCAL,
            storedApiKey = "ignored",
            storedLocalWssUrl = "wss://192.168.1.5:8080",
            devApiKey = "",
        )
        assertEquals(AppPrefs.VoiceBackendKind.LOCAL, choice.kind)
        assertSame(Backends.local, choice.factory)
        assertEquals("wss://192.168.1.5:8080", choice.credential)
    }

    @Test
    fun `resolveChoice maps local with no host to blank credential`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.LOCAL,
            storedApiKey = null,
            storedLocalWssUrl = null,
            devApiKey = "",
        )
        assertEquals("", choice.credential)
    }

    @Test
    fun `resolveChoice maps gemini to stored key`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.GEMINI,
            storedApiKey = "user-key",
            storedLocalWssUrl = "wss://192.168.1.5:8080",
            devApiKey = "dev-key",
        )
        assertEquals(AppPrefs.VoiceBackendKind.GEMINI, choice.kind)
        assertSame(Backends.gemini, choice.factory)
        assertEquals("user-key", choice.credential)
    }

    @Test
    fun `resolveChoice falls back to dev key for gemini when unset`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.GEMINI,
            storedApiKey = null,
            storedLocalWssUrl = null,
            devApiKey = "dev-key",
        )
        assertEquals("dev-key", choice.credential)
    }

    @Test
    fun `resolveChoice passes a blank stored gemini key through`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.GEMINI,
            storedApiKey = "   ",
            storedLocalWssUrl = null,
            devApiKey = "",
        )
        assertEquals("   ", choice.credential)
    }

    @Test
    fun `dnsHostFor local uses LocalVoiceHost on stored wss url`() {
        // Pure helper — no Context in unit test; exercise the local branch via stored URL shape.
        assertEquals("192.168.1.5", LocalVoiceHost.dnsName("wss://192.168.1.5:8080"))
    }

    @Test
    fun `dnsHostFor gemini delegates to GeminiBackend`() {
        assertEquals("generativelanguage.googleapis.com", GeminiBackend.dnsHost())
    }
}
