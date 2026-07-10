package com.portal.assistant.conversation.backend

import com.portal.assistant.gemini.GeminiBackend
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.LocalVoiceHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendsTest {

    @Test
    fun `missing credential message is backend-specific`() {
        assertEquals(
            "Jarvis isn’t set up yet — a Gemini API key is missing.",
            CredentialMessages.missing(AppPrefs.VoiceBackendKind.GEMINI),
        )
        assertEquals(
            "Jarvis isn’t set up yet — add your local server address in Settings.",
            CredentialMessages.missing(AppPrefs.VoiceBackendKind.LOCAL),
        )
        assertEquals(
            CredentialMessages.missing(AppPrefs.VoiceBackendKind.GEMINI),
            Backends.missingCredentialMessage(AppPrefs.VoiceBackendKind.GEMINI),
        )
    }

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
        assertEquals(LocalWireOptions(), choice.defaultWire.local)
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
        assertTrue(choice.credentialMissing)
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
        assertEquals(GeminiWireOptions(), choice.defaultWire.gemini)
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
    fun `dnsHostFor local uses LocalVoiceHost on stored wss url`() {
        // Pure helper — no Context in unit test; exercise the local branch via stored URL shape.
        assertEquals("192.168.1.5", LocalVoiceHost.dnsName("wss://192.168.1.5:8080"))
    }

    @Test
    fun `dnsHostFor gemini delegates to GeminiBackend`() {
        assertEquals("generativelanguage.googleapis.com", GeminiBackend.dnsHost())
    }

    @Test
    fun `BackendWireDefaults applyTo merges wire slices into config`() {
        val localWire = LocalWireOptions(tls = LocalTlsMode.SYSTEM)
        val defaults = Backends.BackendWireDefaults(local = localWire)
        val merged = defaults.applyTo(
            BackendConfig(credential = "wss://10.0.0.1:1", model = "", systemPrompt = "p"),
        )
        assertEquals(localWire, merged.local)
        assertEquals(GeminiWireOptions(), merged.gemini)
    }
}
