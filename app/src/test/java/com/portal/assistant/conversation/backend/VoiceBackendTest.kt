package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.gemini.GeminiBackend
import com.portal.assistant.local.LocalBackend
import com.portal.assistant.util.Http
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the model-neutral [VoiceBackend] seam. The engine is Android-bound (Handler/Looper,
 * AudioTrack, overlay), so rather than spin it up under Robolectric we test the *contract* it depends
 * on: any implementation can be driven through the full turn lifecycle, and the Gemini adapter maps a
 * neutral [BackendConfig] onto the shared transport.
 */
class VoiceBackendTest {

    /** A minimal in-memory [VoiceBackend] that records calls and lets a test drive its [listener]. */
    private class FakeVoiceBackend(val listener: VoiceBackend.Listener) : VoiceBackend {
        val sentAudio = mutableListOf<ByteArray>()
        val sentText = mutableListOf<String>()
        val sentToolResponses = mutableListOf<List<ToolResult>>()
        var connected = false
        var closed = false

        override fun connect() {
            connected = true
        }
        override fun sendAudio(pcm: ByteArray) {
            sentAudio += pcm
        }
        override fun sendText(text: String) {
            sentText += text
        }
        override fun sendToolResponse(results: List<ToolResult>) {
            sentToolResponses += results
        }
        override fun close() {
            closed = true
        }
    }

    /** Records every event the seam raises, so a test can assert the engine-facing surface. */
    private class RecordingListener : VoiceBackend.Listener {
        val events = mutableListOf<BackendEvent>()
        override fun onEvent(event: BackendEvent) {
            events += event
        }
    }

    @Test
    fun `factory receives config and listener, backend drives the full turn`() {
        val listener = RecordingListener()
        var seenConfig: BackendConfig? = null
        val factory = object : VoiceBackendFactory {
            override val deadAirStallMs = 5_000L
            override fun create(config: BackendConfig, listener: VoiceBackend.Listener): VoiceBackend {
                seenConfig = config
                return FakeVoiceBackend(listener)
            }
        }

        val config = BackendConfig(
            credential = "cred",
            model = "some-model",
            systemPrompt = "be helpful",
            functionDeclarations = listOf(JSONObject().put("name", "portal.get_time")),
        )
        val backend = factory.create(config, listener) as FakeVoiceBackend

        // The factory got exactly the config we passed (no Gemini-shaped translation leaks up here).
        assertSame(config, seenConfig)
        assertEquals("some-model", seenConfig!!.model)

        // Engine-side operations reach the backend.
        backend.connect()
        backend.sendAudio(byteArrayOf(1, 2, 3))
        backend.sendText("hi")
        backend.sendToolResponse(listOf(ToolResult("id1", "portal.get_time", JSONObject().put("ok", true))))
        assertTrue(backend.connected)
        assertEquals(1, backend.sentAudio.size)
        assertEquals(listOf("hi"), backend.sentText)
        assertEquals(1, backend.sentToolResponses.single().size)

        // Backend-side events reach the listener, in order, across a whole turn.
        val turn = listOf(
            BackendEvent.Ready,
            BackendEvent.InputTranscript("set a timer"),
            BackendEvent.ModelGenerating,
            BackendEvent.ToolCall(listOf(FunctionCall("id1", "portal.set_timer", JSONObject()))),
            BackendEvent.OutputTranscript("Timer set."),
            BackendEvent.Audio(ByteArray(480)),
            BackendEvent.TurnComplete,
            BackendEvent.Closed,
        )
        turn.forEach(backend.listener::onEvent)

        assertEquals(turn, listener.events)

        backend.close()
        assertTrue(backend.closed)
    }

    @Test
    fun `Gemini adapter runs the Gemini wire format on the shared transport`() {
        val backend = GeminiBackend.Factory.create(
            BackendConfig(credential = "key", model = "m", systemPrompt = "p"),
            RecordingListener(),
        )
        assertTrue("Gemini backend should be a WebSocketVoiceBackend", backend is WebSocketVoiceBackend)
    }

    private fun geminiGenerationConfig(config: BackendConfig): JSONObject =
        JSONObject((GeminiBackend.wireFormat(config).setup() as Frame.Text).text)
            .getJSONObject("setup").getJSONObject("generationConfig")

    @Test
    fun `Gemini adapter speaks with the chosen speaker`() {
        val voiceName = geminiGenerationConfig(
            BackendConfig(credential = "key", model = "m", speaker = "Charon", systemPrompt = "p"),
        )
            .getJSONObject("speechConfig").getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig").getString("voiceName")
        assertEquals("Charon", voiceName)
    }

    @Test
    fun `Gemini adapter leaves server default to Gemini`() {
        val generationConfig =
            geminiGenerationConfig(BackendConfig(credential = "key", model = "m", speaker = null, systemPrompt = "p"))
        assertFalse(generationConfig.has("speechConfig"))
    }

    @Test
    fun `Gemini adapter URL-encodes the API key into the default endpoint`() {
        val address = GeminiBackend.connectAddress(
            BackendConfig(credential = "a+b/c=d&e f", model = "m", systemPrompt = "p"),
        )
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService" +
                ".BidiGenerateContent?key=a%2Bb%2Fc%3Dd%26e%20f",
            address,
        )
    }

    @Test
    fun `Gemini adapter connects to a configured endpoint`() {
        val address = GeminiBackend.connectAddress(
            BackendConfig(
                credential = "a+b",
                model = "m",
                systemPrompt = "p",
                gemini = GeminiWireOptions(endpointUrl = "wss://example.test/live"),
            ),
        )
        assertEquals("wss://example.test/live?key=a%2Bb", address)
    }

    @Test
    fun `Gemini adapter tolerates a null credential`() {
        // A local backend needs no credential; the adapter must not NPE mapping it into Gemini's connect address.
        val address = GeminiBackend.connectAddress(BackendConfig(credential = null, model = "m", systemPrompt = "p"))
        assertTrue(address.endsWith("?key="))
    }

    @Test
    fun `Gemini DNS warm-up derives the Gemini host`() {
        assertEquals("generativelanguage.googleapis.com", GeminiBackend.dnsHost())
    }

    @Test
    fun `Local adapter runs the local wire format on the shared transport`() {
        val backend = LocalBackend.Factory.create(
            BackendConfig(credential = "wss://192.168.1.5:8080", model = "ignored", systemPrompt = "p"),
            RecordingListener(),
        )
        assertTrue("Local backend should be a WebSocketVoiceBackend", backend is WebSocketVoiceBackend)
    }

    @Test
    fun `Local adapter turns a null credential into one error on connect, not a crash`() {
        val listener = RecordingListener()
        val backend = LocalBackend.Factory.create(
            BackendConfig(credential = null, model = "m", systemPrompt = "p"),
            listener,
        )
        backend.connect()
        val failure = (listener.events.single() as BackendEvent.Failed).error as BackendError.Transport
        assertTrue(failure.detail.startsWith("Local connection failed: "))
    }

    @Test
    fun `Local adapter picks the TLS trust from the local wire options`() {
        assertSame(Http.lanVoice, LocalBackend.tlsClient(LocalWireOptions(LocalTlsMode.TRUST_SELF_SIGNED)))
        assertSame(Http.shared, LocalBackend.tlsClient(LocalWireOptions(LocalTlsMode.SYSTEM)))
    }

    @Test
    fun `each shipping factory declares a dead-air stall budget`() {
        assertEquals(GeminiBackend.DEAD_AIR_STALL_MS, GeminiBackend.Factory.deadAirStallMs)
        assertEquals(LocalBackend.DEAD_AIR_STALL_MS, LocalBackend.Factory.deadAirStallMs)
        assertTrue(LocalBackend.Factory.deadAirStallMs > GeminiBackend.Factory.deadAirStallMs)
    }

    @Test
    fun `Backends default is the Gemini factory`() {
        assertSame(GeminiBackend.Factory, Backends.default)
    }
}
