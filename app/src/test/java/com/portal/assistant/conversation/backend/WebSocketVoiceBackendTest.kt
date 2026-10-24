package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The shared transport's socket lifecycle, driven synchronously: a [FakeWebSocket] records what the transport
 * sends, and the test calls the transport's real [WebSocketListener] callbacks (captured from the dial) by hand.
 * The [TestWireFormat] encodes frames as readable tags so assertions read as the frame sequence.
 */
class WebSocketVoiceBackendTest {

    private class FakeWebSocket(private val request: Request) : WebSocket {
        val sent = mutableListOf<String>()
        val closes = mutableListOf<Int>()
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean {
            sent += text
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            sent += "bin:${bytes.size}"
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            closes += code
            return true
        }
        override fun cancel() = Unit
    }

    /** Frames are tags; text frames decode as a comma-separated list of event tags. */
    private class TestWireFormat : WireFormat {
        override val label = "Test"
        override fun setup() = Frame.Text("setup")
        override fun audio(pcm: ByteArray) = Frame.Binary(pcm)
        override fun userText(text: String) = Frame.Text("text:$text")
        override fun toolResults(results: List<ToolResult>) = Frame.Text("tools:${results.map { it.id }}")
        override fun decode(text: String): List<BackendEvent> = text.split(",").map { tag ->
            when (tag) {
                "ready" -> BackendEvent.Ready
                "generating" -> BackendEvent.ModelGenerating
                "audio" -> BackendEvent.Audio(ByteArray(4))
                "turnComplete" -> BackendEvent.TurnComplete
                "interrupted" -> BackendEvent.Interrupted
                "in" -> BackendEvent.InputTranscript("hi")
                "out" -> BackendEvent.OutputTranscript("hello")
                "toolCall" -> BackendEvent.ToolCall(listOf(CALL))
                "toolCancel" -> BackendEvent.ToolCancel(listOf("a"))
                "closingSoon" -> BackendEvent.ClosingSoon(5_000)
                "error" -> BackendEvent.Failed(BackendError.Server("boom"))
                else -> error("unknown tag $tag")
            }
        }
        override fun decode(bytes: ByteArray): List<BackendEvent> = listOf(BackendEvent.Audio(bytes))
        override fun closeMessage(code: Int, reason: String) = reason.takeIf { code == 4000 }

        companion object {
            /** One instance: [FunctionCall]'s JSON arguments compare by identity. */
            val CALL = FunctionCall("a", "portal.get_time", JSONObject())
        }
    }

    private class RecordingListener : VoiceBackend.Listener {
        val events = mutableListOf<BackendEvent>()
        override fun onEvent(event: BackendEvent) {
            events += event
        }
    }

    // The events the test wire format decodes its tags into, and the transport's own.
    private val ready = BackendEvent.Ready
    private val generating = BackendEvent.ModelGenerating
    private val audio4 = BackendEvent.Audio(ByteArray(4))
    private val turnComplete = BackendEvent.TurnComplete
    private val closed = BackendEvent.Closed

    private fun transportFailure(detail: String) = BackendEvent.Failed(BackendError.Transport(detail))

    private val listener = RecordingListener()
    private var dials = 0
    private lateinit var socket: FakeWebSocket
    private lateinit var socketListener: WebSocketListener

    private fun transport(address: String = "wss://192.168.1.5:8080") = WebSocketVoiceBackend(
        wireFormat = TestWireFormat(),
        address = address,
        listener = listener,
        dial = { request, socketListener ->
            dials++
            this.socketListener = socketListener
            FakeWebSocket(request).also { socket = it }
        },
    )

    private fun openResponse(request: Request) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build()

    /** Dial and open the socket; returns the transport with its setup sent. */
    private fun opened(): WebSocketVoiceBackend = transport().also {
        it.connect()
        socketListener.onOpen(socket, openResponse(socket.request()))
    }

    @Test
    fun `open sends the encoded setup frame`() {
        opened()
        assertEquals(listOf("setup"), socket.sent)
    }

    private val toolResult = listOf(ToolResult("a", "portal.get_time", JSONObject()))

    private fun WebSocketVoiceBackend.sendOneOfEach() {
        sendAudio(ByteArray(8))
        sendText("hi")
        sendToolResponse(toolResult)
    }

    @Test
    fun `audio, text and tool results are held back until ready`() {
        val backend = opened()
        backend.sendOneOfEach()
        assertEquals(listOf("setup"), socket.sent)
    }

    @Test
    fun `ready is relayed and opens the gate for every send`() {
        val backend = opened()
        socketListener.onMessage(socket, "ready")
        backend.sendOneOfEach()
        assertEquals(listOf(ready), listener.events)
        assertEquals(listOf("setup", "bin:8", "text:hi", "tools:[a]"), socket.sent)
    }

    @Test
    fun `every backend event is handed on as is, in order`() {
        opened()
        socketListener.onMessage(
            socket,
            "ready,in,generating,toolCall,toolCancel,out,audio,interrupted,closingSoon,turnComplete,error",
        )
        assertEquals(
            listOf(
                ready,
                BackendEvent.InputTranscript("hi"),
                generating,
                BackendEvent.ToolCall(listOf(TestWireFormat.CALL)),
                BackendEvent.ToolCancel(listOf("a")),
                BackendEvent.OutputTranscript("hello"),
                audio4,
                BackendEvent.Interrupted,
                BackendEvent.ClosingSoon(5_000),
                turnComplete,
                BackendEvent.Failed(BackendError.Server("boom")),
            ),
            listener.events,
        )
    }

    @Test
    fun `a binary frame is decoded by the wire format and relayed`() {
        opened()
        socketListener.onMessage(socket, "ready,generating")
        socketListener.onMessage(socket, ByteString.of(*ByteArray(6)))
        assertEquals(listOf(ready, generating, BackendEvent.Audio(ByteArray(6))), listener.events)
    }

    @Test
    fun `model generating is reported once before the first audio of a turn`() {
        opened()
        socketListener.onMessage(socket, "ready,audio,audio")
        assertEquals(listOf(ready, generating, audio4, audio4), listener.events)
    }

    @Test
    fun `no extra model generating when the wire format already reported it`() {
        opened()
        socketListener.onMessage(socket, "ready,generating,audio")
        assertEquals(listOf(ready, generating, audio4), listener.events)
    }

    @Test
    fun `every turn gets the first-audio guarantee after turn complete`() {
        opened()
        socketListener.onMessage(socket, "ready,audio,turnComplete,audio")
        assertEquals(listOf(ready, generating, audio4, turnComplete, generating, audio4), listener.events)
    }

    @Test
    fun `failure reports one labelled transport failure and closes the gate`() {
        val backend = opened()
        socketListener.onMessage(socket, "ready")
        socketListener.onFailure(socket, IOException("connection reset"), null)
        backend.sendOneOfEach()
        assertEquals(listOf(ready, transportFailure("Test connection failed: connection reset")), listener.events)
        assertEquals(listOf("setup"), socket.sent)
    }

    @Test
    fun `a failure with no message names the exception`() {
        opened()
        socketListener.onFailure(socket, IOException(), null)
        assertEquals(listOf(transportFailure("Test connection failed: IOException")), listener.events)
    }

    @Test
    fun `failure resets the first-audio latch`() {
        opened()
        socketListener.onMessage(socket, "ready,generating")
        socketListener.onFailure(socket, IOException("x"), null)
        socketListener.onMessage(socket, "audio")
        assertEquals(
            listOf(ready, generating, transportFailure("Test connection failed: x"), generating, audio4),
            listener.events,
        )
    }

    @Test
    fun `closed reports closed and closes the gate`() {
        val backend = opened()
        socketListener.onMessage(socket, "ready,generating")
        socketListener.onClosed(socket, 1000, "bye")
        backend.sendOneOfEach()
        socketListener.onMessage(socket, "audio")
        assertEquals(listOf(ready, generating, closed, generating, audio4), listener.events)
        assertEquals(listOf("setup"), socket.sent)
    }

    @Test
    fun `a close the wire format gives a message for is reported as a server message, then closed`() {
        opened()
        socketListener.onClosing(socket, 4000, "key rejected")
        socketListener.onClosed(socket, 4000, "key rejected")
        assertEquals(listOf(BackendEvent.Failed(BackendError.Server("key rejected")), closed), listener.events)
    }

    @Test
    fun `a close the wire format gives no message for reports no error`() {
        opened()
        socketListener.onClosing(socket, 1011, "internal")
        socketListener.onClosed(socket, 1011, "internal")
        assertEquals(listOf(closed), listener.events)
    }

    @Test
    fun `closing replies with a normal close`() {
        opened()
        socketListener.onClosing(socket, 1001, "going away")
        assertEquals(listOf(1000), socket.closes)
    }

    @Test
    fun `a malformed address gives one transport failure and never dials`() {
        transport(address = "not an address").connect()
        val failure = (listener.events.single() as BackendEvent.Failed).error as BackendError.Transport
        assertEquals(true, failure.detail.startsWith("Test connection failed: "))
        assertEquals(0, dials)
    }

    @Test
    fun `close is idempotent and stops further sends`() {
        val backend = opened()
        socketListener.onMessage(socket, "ready")
        backend.close()
        backend.close()
        backend.sendOneOfEach()
        assertEquals(listOf(1000), socket.closes)
        assertEquals(listOf("setup"), socket.sent)
    }

    @Test
    fun `the client's own close resets the gate and the first-audio latch`() {
        val backend = opened()
        socketListener.onMessage(socket, "ready,generating")
        backend.close()
        backend.connect()
        socketListener.onOpen(socket, openResponse(socket.request()))
        backend.sendOneOfEach()
        socketListener.onMessage(socket, "audio")
        assertEquals(listOf(ready, generating, generating, audio4), listener.events)
        assertEquals(listOf("setup"), socket.sent)
    }

    @Test
    fun `the socket client carries the shared voice socket settings`() {
        val client = WebSocketVoiceBackend.socketClient(okhttp3.OkHttpClient())
        assertEquals(20_000, client.pingIntervalMillis)
        assertEquals(0, client.readTimeoutMillis)
        assertEquals(15_000, client.connectTimeoutMillis)
    }
}
