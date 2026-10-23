package com.portal.assistant.gemini

import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.Frame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pins Gemini Live's wire format: inbound messages → neutral [BackendEvent]s and the outbound frames. Pure, so no
 * Android/Robolectric — the socket, the setup gate and the relay are the shared transport's
 * (`WebSocketVoiceBackendTest`).
 */
class GeminiWireFormatTest {

    private val wireFormat = GeminiWireFormat(model = "m", systemPrompt = "p")

    private fun decode(text: String) = wireFormat.decode(text)

    private fun Frame.json() = JSONObject((this as Frame.Text).text)

    private fun setupOf(
        model: String = "m",
        systemPrompt: String = "p",
        functionDeclarations: List<JSONObject> = emptyList(),
        speaker: String? = null,
    ): JSONObject = GeminiWireFormat(model, systemPrompt, functionDeclarations, speaker).setup().json().getJSONObject("setup")

    // ---- decode ------------------------------------------------------------------------------------

    @Test fun setupCompleteIsReady() {
        assertEquals(listOf(BackendEvent.Ready), decode("""{"setupComplete":{}}"""))
    }

    @Test fun garbageOrEmptyIsNoEvents() {
        assertEquals(emptyList<BackendEvent>(), decode("not json"))
        assertEquals(emptyList<BackendEvent>(), decode("{}"))
        assertEquals(emptyList<BackendEvent>(), decode("""{"serverContent":{}}"""))
    }

    @Test fun binaryFrameIsReadAsUtf8Json() {
        assertEquals(listOf(BackendEvent.Ready), wireFormat.decode("""{"setupComplete":{}}""".toByteArray(Charsets.UTF_8)))
        assertEquals(
            listOf(BackendEvent.OutputTranscript("café")),
            wireFormat.decode("""{"serverContent":{"outputTranscription":{"text":"café"}}}""".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(emptyList<BackendEvent>(), wireFormat.decode(byteArrayOf(1, 2, 3)))
    }

    @Test fun inputTranscriptParsed() {
        val events = decode("""{"serverContent":{"inputTranscription":{"text":"what's the weather"}}}""")
        assertEquals(listOf(BackendEvent.InputTranscript("what's the weather")), events)
    }

    @Test fun emptyTranscriptIsDropped() {
        assertEquals(emptyList<BackendEvent>(), decode("""{"serverContent":{"inputTranscription":{"text":""}}}"""))
    }

    @Test fun outputTranscriptParsed() {
        val events = decode("""{"serverContent":{"outputTranscription":{"text":"Let me tell you"}}}""")
        assertEquals(listOf(BackendEvent.OutputTranscript("Let me tell you")), events)
    }

    @Test fun emptyOutputTranscriptIsDropped() {
        assertEquals(emptyList<BackendEvent>(), decode("""{"serverContent":{"outputTranscription":{"text":""}}}"""))
    }

    @Test fun modelTurnAudioThenOutputTranscriptInOrder() {
        val pcm = byteArrayOf(5, 6)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val json =
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"data":"$b64"}}]},""" +
                """"outputTranscription":{"text":"hello"},"turnComplete":true}}"""
        assertEquals(
            listOf(
                BackendEvent.ModelGenerating,
                BackendEvent.Audio(pcm),
                BackendEvent.OutputTranscript("hello"),
                BackendEvent.TurnComplete,
            ),
            decode(json),
        )
    }

    @Test fun modelTurnWithAudioEmitsGeneratingThenAudio() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val json =
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm","data":"$b64"}}]}}}"""
        assertEquals(listOf(BackendEvent.ModelGenerating, BackendEvent.Audio(pcm)), decode(json))
    }

    @Test fun textOnlyModelTurnEmitsGeneratingOnly() {
        // A "thinking"/text part with no inlineData — model took the turn but no audio yet.
        val json = """{"serverContent":{"modelTurn":{"parts":[{"text":"thinking"}]}}}"""
        assertEquals(listOf(BackendEvent.ModelGenerating), decode(json))
    }

    @Test fun turnCompleteParsed() {
        assertEquals(listOf(BackendEvent.TurnComplete), decode("""{"serverContent":{"turnComplete":true}}"""))
    }

    @Test fun interruptedParsed() {
        assertEquals(listOf(BackendEvent.Interrupted), decode("""{"serverContent":{"interrupted":true}}"""))
    }

    @Test fun goAwayIsClosingSoonInMillis() {
        assertEquals(listOf(BackendEvent.ClosingSoon(9500L)), decode("""{"goAway":{"timeLeft":"9.5s"}}"""))
    }

    @Test fun combinedMessagePreservesOrder() {
        // input transcript, then model takes the turn, then turnComplete — in document order.
        val json =
            """{"serverContent":{"inputTranscription":{"text":"hi"},"modelTurn":{"parts":[{"text":"x"}]},"turnComplete":true}}"""
        assertEquals(
            listOf(BackendEvent.InputTranscript("hi"), BackendEvent.ModelGenerating, BackendEvent.TurnComplete),
            decode(json),
        )
    }

    // ---- toolCall / toolCallCancellation -----------------------------------------------------------

    @Test fun toolCallOnlyPayload() {
        // toolCall with no serverContent → ToolCall event, not empty list
        val events = decode("""{"toolCall":{"functionCalls":[{"id":"c1","name":"portal.get_time","args":{}}]}}""")
        val toolCall = events.single() as BackendEvent.ToolCall
        assertEquals(listOf("c1"), toolCall.calls.map { it.id })
        assertEquals(listOf("portal.get_time"), toolCall.calls.map { it.name })
    }

    @Test fun toolCallArgsAsString() {
        // args delivered as a JSON-encoded string → defensively unpacked to JSONObject
        val events = decode(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"portal.get_weather","args":"{\"unit\":\"c\"}"}]}}""",
        )
        assertEquals("c", (events.single() as BackendEvent.ToolCall).calls[0].args.optString("unit"))
    }

    @Test fun toolCallMissingOrGarbageArgsAreEmpty() {
        val events = decode(
            """{"toolCall":{"functionCalls":[{"id":"a","name":"n"},{"id":"b","name":"n","args":"not json"}]}}""",
        )
        assertEquals(listOf(0, 0), (events.single() as BackendEvent.ToolCall).calls.map { it.args.length() })
    }

    @Test fun toolCallWithNoNameIsKept() {
        // Kept so the Tool runner answers it ("unknown tool") — every call gets exactly one answer.
        val events = decode("""{"toolCall":{"functionCalls":[{"id":"c1","args":{}}]}}""")
        val call = (events.single() as BackendEvent.ToolCall).calls.single()
        assertEquals("c1", call.id)
        assertEquals("", call.name)
    }

    @Test fun toolCallCancellationParsed() {
        assertEquals(listOf(BackendEvent.ToolCancel(listOf("c1", "c2"))), decode("""{"toolCallCancellation":{"ids":["c1","c2"]}}"""))
    }

    @Test fun toolCallAndServerContentSameMessage() {
        // Same message can carry both toolCall and serverContent — merged in document order.
        val events = decode(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"n","args":{}}]},""" +
                """"serverContent":{"turnComplete":true}}""",
        )
        assertEquals(2, events.size)
        assertTrue(events[0] is BackendEvent.ToolCall)
        assertEquals(BackendEvent.TurnComplete, events[1])
    }

    // ---- setup -------------------------------------------------------------------------------------

    @Test fun setupHasAudioGoogleSearchTranscriptionAndVad() {
        val setup = setupOf(model = "gemini-2.5-flash-native-audio-latest", systemPrompt = "be nice")

        assertEquals("models/gemini-2.5-flash-native-audio-latest", setup.getString("model"))
        assertEquals(
            "AUDIO",
            setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0),
        )
        assertEquals(
            "be nice",
            setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"),
        )
        // googleSearch present; no functionDeclarations entry when none are passed.
        val tools = setup.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        assertFalse(tools.getJSONObject(0).has("functionDeclarations"))
        // Both transcriptions on: input drives the user-speech signal, output drives the chat text.
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        // VAD block.
        val vad = setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals(300, vad.getInt("prefixPaddingMs"))
        assertEquals(600, vad.getInt("silenceDurationMs"))
    }

    @Test fun serverDefaultSetupHasNoSpeechConfig() {
        // No Speaker chosen → Gemini picks its own voice: generationConfig carries only the modality.
        val generationConfig = setupOf(speaker = null).getJSONObject("generationConfig")
        assertFalse(generationConfig.has("speechConfig"))
        assertEquals(setOf("responseModalities"), generationConfig.keys().asSequence().toSet())
    }

    @Test fun chosenSpeakerSetsThePrebuiltVoiceName() {
        val generationConfig = setupOf(speaker = "Charon").getJSONObject("generationConfig")
        assertEquals(
            "Charon",
            generationConfig.getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
        // The Speaker rides alongside the audio modality, not instead of it.
        assertEquals("AUDIO", generationConfig.getJSONArray("responseModalities").getString(0))
    }

    @Test fun setupKeepsExistingModelsPrefix() {
        assertEquals("models/foo", setupOf(model = "models/foo").getString("model"))
    }

    @Test fun declarationsAddSecondToolsEntry() {
        val decl = JSONObject("""{"name":"portal._spike_noop","description":"test"}""")
        val tools = setupOf(functionDeclarations = listOf(decl)).getJSONArray("tools")
        assertEquals(2, tools.length())
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        val declArray = tools.getJSONObject(1).getJSONArray("functionDeclarations")
        assertEquals(1, declArray.length())
        assertEquals("portal._spike_noop", declArray.getJSONObject(0).getString("name"))
    }

    // ---- outbound frames ---------------------------------------------------------------------------

    @Test fun audioIsBase64PcmInARealtimeInputChunk() {
        val chunk = wireFormat.audio(byteArrayOf(1, 2, 3)).json()
            .getJSONObject("realtimeInput").getJSONArray("mediaChunks").getJSONObject(0)
        assertEquals("audio/pcm;rate=16000", chunk.getString("mimeType"))
        assertEquals("AQID", chunk.getString("data"))
    }

    @Test fun userTextIsACompleteUserTurn() {
        val clientContent = wireFormat.userText("what's the weather?").json().getJSONObject("clientContent")
        assertTrue(clientContent.getBoolean("turnComplete")) // finished turn → model answers without a VAD wait
        val turn = clientContent.getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals("what's the weather?", turn.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun toolResultsAreFunctionResponsesInOrder() {
        val responses = wireFormat.toolResults(
            listOf(
                ToolResult("a", "portal.set_timer", JSONObject().put("ok", true)),
                ToolResult("b", "portal.get_time", JSONObject().put("time", "10:00")),
            ),
        ).json().getJSONObject("toolResponse").getJSONArray("functionResponses")
        assertEquals(2, responses.length())
        assertEquals("a", responses.getJSONObject(0).getString("id"))
        assertEquals("portal.set_timer", responses.getJSONObject(0).getString("name"))
        assertTrue(responses.getJSONObject(0).getJSONObject("response").getBoolean("ok"))
        assertEquals("b", responses.getJSONObject(1).getString("id"))
    }

    @Test fun labelIsLive() {
        // Keeps the failure text "Live connection failed: …".
        assertEquals("Live", wireFormat.label)
    }

    // ---- parseDurationMs ---------------------------------------------------------------------------

    @Test fun durationParsing() {
        assertEquals(9500L, GeminiWireFormat.parseDurationMs("9.5s"))
        assertEquals(800L, GeminiWireFormat.parseDurationMs("0.8s"))
        assertEquals(10000L, GeminiWireFormat.parseDurationMs("10s"))
        assertEquals(0L, GeminiWireFormat.parseDurationMs(""))
        assertEquals(0L, GeminiWireFormat.parseDurationMs("garbage"))
    }

    @Test
    fun `a non-normal close reason is a message for the user, such as a rejected key`() {
        assertEquals(
            "API key not valid. Please pass a valid API key.",
            wireFormat.closeMessage(1007, "API key not valid. Please pass a valid API key."),
        )
    }

    @Test
    fun `a normal close or a blank reason is no message`() {
        assertEquals(null, wireFormat.closeMessage(1000, "bye"))
        assertEquals(null, wireFormat.closeMessage(1011, " "))
    }
}
