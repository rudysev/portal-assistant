package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.backend.LocalWireOptions
import com.portal.assistant.system.AppPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendSessionConfigTest {

    @Test
    fun `build merges choice credential kind and wire defaults`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.LOCAL,
            storedApiKey = null,
            storedLocalWssUrl = "wss://192.168.1.5:8080",
            devApiKey = "",
        )
        val config = BackendSessionConfig.build(
            choice = choice,
            model = "ignored-for-local",
            systemPrompt = "be helpful",
            functionDeclarations = listOf(JSONObject().put("name", "portal.get_time")),
        )
        assertEquals("wss://192.168.1.5:8080", config.credential)
        assertEquals(AppPrefs.VoiceBackendKind.LOCAL, config.kind)
        assertEquals("be helpful", config.systemPrompt)
        assertEquals(LocalWireOptions(), config.local)
    }

    @Test
    fun `build carries gemini model and wire slice`() {
        val choice = Backends.resolveChoice(
            selected = AppPrefs.VoiceBackendKind.GEMINI,
            storedApiKey = "key",
            storedLocalWssUrl = null,
            devApiKey = "",
        )
        val config = BackendSessionConfig.build(
            choice = choice,
            model = "gemini-2.5-flash-native-audio-latest",
            systemPrompt = "p",
            functionDeclarations = emptyList(),
        )
        assertEquals("key", config.credential)
        assertEquals("gemini-2.5-flash-native-audio-latest", config.model)
        assertEquals(AppPrefs.VoiceBackendKind.GEMINI, config.kind)
    }
}
