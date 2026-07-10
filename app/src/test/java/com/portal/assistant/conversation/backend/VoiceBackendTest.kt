package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.local.LocalBackend
import com.portal.assistant.gemini.GeminiBackend
import com.portal.assistant.gemini.LiveClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the model-neutral [VoiceBackend] seam. The engine is Android-bound (Handler/Looper,
 * AudioTrack, overlay), so rather than spin it up under Robolectric we test the *contract* it depends
 * on: any implementation can be driven through the full turn lifecycle, and the Gemini adapter maps a
 * neutral [BackendConfig] onto [LiveClient].
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

    /** Records every callback the seam raises, so a test can assert the engine-facing surface. */
    private class RecordingListener : VoiceBackend.Listener {
        val events = mutableListOf<String>()
        val audio = mutableListOf<ByteArray>()
        override fun onReady() {
            events += "ready"
        }
        override fun onInputTranscript(textDelta: String) {
            events += "in:$textDelta"
        }
        override fun onOutputTranscript(textDelta: String) {
            events += "out:$textDelta"
        }
        override fun onAudio(pcm24k: ByteArray) {
            events += "audio:${pcm24k.size}"
            audio += pcm24k
        }
        override fun onTurnComplete() {
            events += "turnComplete"
        }
        override fun onInterrupted() {
            events += "interrupted"
        }
        override fun onModelGenerating() {
            events += "generating"
        }
        override fun onToolCall(calls: List<FunctionCall>) {
            events += "toolCall:${calls.map { it.name }}"
        }
        override fun onToolCancel(ids: List<String>) {
            events += "toolCancel:$ids"
        }
        override fun onServerClosingSoon(graceMs: Long) {
            events += "closingSoon:$graceMs"
        }
        override fun onError(message: String) {
            events += "error:$message"
        }
        override fun onClosed() {
            events += "closed"
        }
    }

    @Test
    fun `factory receives config and listener, backend drives the full turn`() {
        val listener = RecordingListener()
        var seenConfig: BackendConfig? = null
        val factory = object : VoiceBackendFactory {
            override val deadAirStallMs = 5_000L
            override fun create(config: BackendConfig, l: VoiceBackend.Listener): VoiceBackend {
                seenConfig = config
                return FakeVoiceBackend(l)
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

        // Backend-side callbacks reach the listener, in order, across a whole turn.
        backend.listener.onReady()
        backend.listener.onInputTranscript("set a timer")
        backend.listener.onModelGenerating()
        backend.listener.onToolCall(listOf(FunctionCall("id1", "portal.set_timer", JSONObject())))
        backend.listener.onOutputTranscript("Timer set.")
        backend.listener.onAudio(ByteArray(480))
        backend.listener.onTurnComplete()
        backend.listener.onClosed()

        assertEquals(
            listOf(
                "ready",
                "in:set a timer",
                "generating",
                "toolCall:[portal.set_timer]",
                "out:Timer set.",
                "audio:480",
                "turnComplete",
                "closed",
            ),
            listener.events,
        )

        backend.close()
        assertTrue(backend.closed)
    }

    @Test
    fun `Gemini adapter maps neutral config onto a LiveClient`() {
        val backend = GeminiBackend.Factory.create(
            BackendConfig(credential = "key", model = "m", systemPrompt = "p"),
            RecordingListener(),
        )
        assertTrue("Gemini backend should be a LiveClient", backend is LiveClient)
    }

    @Test
    fun `Gemini adapter tolerates a null credential`() {
        // A local backend needs no credential; the adapter must not NPE mapping it to Gemini's apiKey.
        val backend = GeminiBackend.Factory.create(
            BackendConfig(credential = null, model = "m", systemPrompt = "p"),
            RecordingListener(),
        )
        assertTrue(backend is LiveClient)
    }

    @Test
    fun `Local adapter maps neutral config onto a LocalBackend`() {
        val backend = LocalBackend.Factory.create(
            BackendConfig(credential = "wss://192.168.1.5:8080", model = "ignored", systemPrompt = "p"),
            RecordingListener(),
        )
        assertTrue("Local backend should be a LocalBackend", backend is LocalBackend)
    }

    @Test
    fun `Local adapter tolerates a null credential`() {
        val backend = LocalBackend.Factory.create(
            BackendConfig(credential = null, model = "m", systemPrompt = "p"),
            RecordingListener(),
        )
        assertTrue(backend is LocalBackend)
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
