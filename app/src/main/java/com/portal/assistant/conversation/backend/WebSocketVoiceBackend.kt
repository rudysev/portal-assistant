package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.util.Http
import com.portal.commons.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The transport every WebSocket voice backend runs on: it owns the socket, and the backend only supplies its
 * [WireFormat] (pure frame encoding/decoding) plus, from its factory, the [address] and the [tls] trust (the base
 * client, e.g. [Http.lanVoice] for a self-signed LAN host). The same rules hold for every backend:
 *  - **Setup:** the socket's open sends the wire format's [WireFormat.setup] frame.
 *  - **Setup gate:** audio, text and tool results are dropped until the backend reports [BackendEvent.Ready]
 *    (the transport opens the gate itself, then relays ready); a dropped text turn is logged.
 *  - **First audio:** [VoiceBackend.Listener.onModelGenerating] is reported before the first audio of every turn
 *    — if the wire format didn't decode one this turn, the transport reports it first.
 *  - **Failure** reports one error, "<label> connection failed: <detail>", and closes the gate — so does an
 *    [address] no request can be built from, without dialling. **Closed** closes the gate and reports closed;
 *    the server's closing is answered with a normal close. [close] is idempotent.
 *
 * Socket callbacks are relayed on the socket's own thread (the engine marshals them). [dial] is the socket seam
 * — production uses [socketClient] of [tls]; tests pass a fake socket and drive the listener by hand.
 */
class WebSocketVoiceBackend(
    private val wireFormat: WireFormat,
    private val address: String,
    private val listener: VoiceBackend.Listener,
    tls: OkHttpClient = Http.shared,
    private val dial: (Request, WebSocketListener) -> WebSocket = socketClient(tls)::newWebSocket,
) : VoiceBackend {

    private val logTag = wireFormat.label.lowercase()

    @Volatile private var socket: WebSocket? = null

    /** The setup gate: opened by the backend's [BackendEvent.Ready]; until then every send is dropped. */
    @Volatile private var setupDone = false

    /**
     * Per-turn latch for the first-audio guarantee: whether [VoiceBackend.Listener.onModelGenerating] has been
     * reported this turn. Reset on turn complete, failure, close and the client's own [close].
     */
    @Volatile private var modelGeneratingReported = false

    override fun connect() {
        val request = runCatching { Request.Builder().url(address).build() }.getOrElse { e ->
            reportFailure(e)
            return
        }
        DebugLog.log("$logTag connecting host=${request.url.host}")
        socket = dial(request, socketListener)
    }

    override fun sendAudio(pcm: ByteArray) {
        val webSocket = socket ?: return
        if (!setupDone) return
        send(webSocket, wireFormat.audio(pcm))
    }

    override fun sendText(text: String) {
        val webSocket = socket
        if (webSocket == null || !setupDone) {
            DebugLog.log("$logTag sendText dropped (socket=${webSocket != null}, setupDone=$setupDone)")
            return
        }
        send(webSocket, wireFormat.userText(text))
    }

    override fun sendToolResponse(results: List<ToolResult>) {
        val webSocket = socket ?: return
        if (!setupDone) return
        send(webSocket, wireFormat.toolResults(results))
    }

    override fun close() {
        runCatching { socket?.close(1000, "client closing") }
        socket = null
        closeGate()
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            DebugLog.log("$logTag ws open http=${response.code} -> setup")
            send(webSocket, wireFormat.setup())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = relay(wireFormat.decode(text))

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = relay(wireFormat.decode(bytes.toByteArray()))

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log("$logTag ws CLOSING code=$code reason=$reason")
            runCatching { webSocket.close(1000, null) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            DebugLog.log("$logTag ws FAILURE: ${t.message} http=${response?.code}")
            reportFailure(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            DebugLog.log("$logTag ws closed code=$code reason=$reason")
            setupDone = false
            modelGeneratingReported = false
            listener.onClosed()
        }
    }

    private fun reportFailure(cause: Throwable) {
        closeGate()
        listener.onError("${wireFormat.label} connection failed: ${cause.message ?: cause.javaClass.simpleName}")
    }

    /** Close the setup gate and reset the first-audio latch — the socket is gone or going. */
    private fun closeGate() {
        setupDone = false
        modelGeneratingReported = false
    }

    private fun relay(events: List<BackendEvent>) {
        for (event in events) {
            when (event) {
                is BackendEvent.Ready -> {
                    setupDone = true
                    DebugLog.log("$logTag ready")
                    listener.onReady()
                }

                is BackendEvent.InputTranscript -> listener.onInputTranscript(event.text)

                is BackendEvent.OutputTranscript -> listener.onOutputTranscript(event.text)

                is BackendEvent.Audio -> {
                    if (!modelGeneratingReported) {
                        modelGeneratingReported = true
                        listener.onModelGenerating()
                    }
                    listener.onAudio(event.pcm24k)
                }

                is BackendEvent.ModelGenerating -> {
                    modelGeneratingReported = true
                    listener.onModelGenerating()
                }

                is BackendEvent.TurnComplete -> {
                    modelGeneratingReported = false
                    listener.onTurnComplete()
                }

                is BackendEvent.Interrupted -> listener.onInterrupted()

                is BackendEvent.ToolCall -> {
                    DebugLog.log("$logTag toolCall ids=${event.calls.map { it.id }}")
                    listener.onToolCall(event.calls)
                }

                is BackendEvent.ToolCancel -> {
                    DebugLog.log("$logTag toolCancel ids=${event.ids}")
                    listener.onToolCancel(event.ids)
                }

                is BackendEvent.ClosingSoon -> {
                    DebugLog.log("$logTag closing soon graceMs=${event.graceMs}")
                    listener.onServerClosingSoon(event.graceMs)
                }

                is BackendEvent.Error -> listener.onError(event.message)
            }
        }
    }

    private fun send(webSocket: WebSocket, frame: Frame) {
        when (frame) {
            is Frame.Text -> webSocket.send(frame.text)
            is Frame.Binary -> webSocket.send(frame.bytes.toByteString())
        }
    }

    companion object {
        /** [base] (its TLS trust) with the socket settings every voice backend shares. */
        fun socketClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // keep the socket open
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
