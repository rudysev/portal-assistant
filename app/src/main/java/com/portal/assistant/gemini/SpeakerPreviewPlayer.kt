package com.portal.assistant.gemini

import android.os.Handler
import android.os.Looper
import com.portal.assistant.audio.PcmPlayer
import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.backend.VoiceBackend
import com.portal.commons.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The thin I/O shell for [SpeakerPreview]: one per open Voice dialog. Every input becomes a
 * [SpeakerPreview.Event] reduced on the **main thread** (socket and player callbacks post there — no locks);
 * this class only carries out the resulting [SpeakerPreview.Action]s and publishes [state] for the rows.
 *
 * Each preview gets its own [LiveClient] + [PcmPlayer], so a stopped preview's stragglers land on a dead
 * session and the reducer drops them by id. Independent of the conversation engine: it never touches the mic,
 * the hub, or the reducer — the dialog reports whether a conversation is live via [setConversationLive].
 *
 * It also holds the audio of each preview that played to completion ([clips]) — the reducer decides when to
 * keep one and when a tap replays it; this class just stores the bytes, and they go when it does (one dialog open).
 *
 * [apiKey] is resolved the same way a conversation's is (blank = none → no connection is ever attempted);
 * [model] is the selected model.
 */
class SpeakerPreviewPlayer(
    private val apiKey: String,
    private val model: String,
    conversationLive: Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(
        SpeakerPreview.State(hasKey = apiKey.isNotBlank(), conversationLive = conversationLive),
    )
    val state: StateFlow<SpeakerPreview.State> = _state.asStateFlow()

    /** The preview in flight — at most one. [client] is null for a replay; [clip] collects a live one's audio
     *  (appended on the socket thread, read on main after its turnComplete). */
    private class Session(
        val id: Long,
        val client: LiveClient?,
        val player: PcmPlayer,
        val clip: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue(),
    )

    private var session: Session? = null

    /** Completed previews' audio, by Speaker (null = the Default row) — ~250 KB each, this dialog only. */
    private val clips = mutableMapOf<GeminiSpeaker.Speaker?, List<ByteArray>>()

    /** The user tapped a row (null = the Default row). Main thread. */
    fun tap(speaker: GeminiSpeaker.Speaker?) = dispatch(SpeakerPreview.Event.Tap(speaker))

    /** Report the conversation's state — on open and on every change. Main thread. */
    fun setConversationLive(live: Boolean) = dispatch(SpeakerPreview.Event.ConversationLive(live))

    /** The dialog closed (any way) — stop playback and close the session. Main thread. */
    fun dismiss() = dispatch(SpeakerPreview.Event.Dismissed)

    private fun post(event: SpeakerPreview.Event) {
        handler.post { dispatch(event) }
    }

    private fun dispatch(event: SpeakerPreview.Event) {
        val decision = SpeakerPreview.reduce(_state.value, event)
        _state.value = decision.state
        decision.actions.forEach(::perform)
    }

    private fun perform(action: SpeakerPreview.Action) {
        when (action) {
            is SpeakerPreview.Action.Connect -> connect(action.id, action.speaker)
            is SpeakerPreview.Action.Replay -> replay(action.id, action.speaker)
            is SpeakerPreview.Action.KeepClip -> current(action.id)?.let { s ->
                clips[action.speaker] = s.clip.toList()
                DebugLog.log("speaker preview ${s.id} kept clip ${pcmMs(s.clip.sumOf { it.size.toLong() })} ms")
            }
            is SpeakerPreview.Action.SendIntro -> current(action.id)?.client?.sendText(SpeakerPreview.INTRO_TEXT)
            is SpeakerPreview.Action.CloseSession -> current(action.id)?.client?.close()
            is SpeakerPreview.Action.Stop -> current(action.id)?.let { s ->
                DebugLog.log("speaker preview ${s.id} stop (played ${pcmMs(s.player.playedBytes())} ms)")
                s.player.setOnDrained(null)
                s.player.stop()
                s.client?.close()
                session = null
            }
        }
    }

    private fun current(id: Long): Session? = session?.takeIf { it.id == id }

    /** Duration of [bytes] of 24 kHz mono 16-bit PCM, for the debug log. */
    private fun pcmMs(bytes: Long): Long = bytes / 48


    private fun connect(id: Long, speaker: GeminiSpeaker.Speaker?) {
        DebugLog.log("speaker preview $id start speaker=${speaker?.name ?: "server default"} model=$model")
        val player = PcmPlayer()
        val clip = ConcurrentLinkedQueue<ByteArray>()
        val client = SpeakerPreview.newClient(apiKey, model, speaker, listenerFor(id, player, clip))
        session = Session(id, client, player, clip)
        player.start()
        player.setOnDrained { post(SpeakerPreview.Event.PlaybackDrained(id)) }
        client.connect()
    }

    private fun replay(id: Long, speaker: GeminiSpeaker.Speaker?) {
        DebugLog.log("speaker preview $id replay speaker=${speaker?.name ?: "server default"}")
        val player = PcmPlayer()
        session = Session(id, client = null, player = player)
        player.start()
        player.setOnDrained { post(SpeakerPreview.Event.PlaybackDrained(id)) }
        clips[speaker].orEmpty().forEach(player::enqueue)
    }

    /** Relays preview [id]'s socket callbacks as events. */
    private fun listenerFor(id: Long, player: PcmPlayer, clip: ConcurrentLinkedQueue<ByteArray>) = object : VoiceBackend.Listener {
        override fun onReady() = post(SpeakerPreview.Event.Ready(id))

        override fun onAudio(pcm24k: ByteArray) {
            // Enqueue synchronously (like the engine) — the posted event only moves the row to "playing".
            // Also keep every chunk, in case this preview plays to completion (KeepClip).
            player.enqueue(pcm24k)
            clip.add(pcm24k)
            post(SpeakerPreview.Event.Audio(id))
        }

        override fun onTurnComplete() = post(SpeakerPreview.Event.TurnComplete(id))

        override fun onError(message: String) {
            DebugLog.log("speaker preview $id error: $message")
            post(SpeakerPreview.Event.Disconnected(id))
        }

        override fun onClosed() = post(SpeakerPreview.Event.Disconnected(id))

        // A one-sentence intro with no function declarations — nothing else matters here.
        override fun onInputTranscript(textDelta: String) {}
        override fun onOutputTranscript(textDelta: String) {}
        override fun onInterrupted() {}
        override fun onModelGenerating() {}
        override fun onToolCall(calls: List<FunctionCall>) {}
        override fun onToolCancel(ids: List<String>) {}
        override fun onServerClosingSoon(graceMs: Long) {}
    }
}
