package com.portal.assistant.local

import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.BackendEvent
import com.portal.assistant.conversation.backend.Frame
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the local voice host's wire format: inbound frames → neutral [BackendEvent]s and the outbound frames.
 * Pure, so no Android/Robolectric — the socket, the setup gate and the relay are the shared transport's
 * (`WebSocketVoiceBackendTest`).
 */
class LocalWireFormatTest {

    private val wireFormat = LocalWireFormat(systemPrompt = "be helpful")

    private fun Frame.json() = JSONObject((this as Frame.Text).text)

    @Test
    fun `decodes each control frame to its event`() {
        assertEquals(listOf(BackendEvent.Ready), wireFormat.decode("""{"type":"ready"}"""))
        assertEquals(listOf(BackendEvent.ModelGenerating), wireFormat.decode("""{"type":"model_generating"}"""))
        assertEquals(listOf(BackendEvent.TurnComplete), wireFormat.decode("""{"type":"turn_complete"}"""))
        assertEquals(listOf(BackendEvent.Interrupted), wireFormat.decode("""{"type":"interrupted"}"""))
        assertEquals(
            listOf(BackendEvent.InputTranscript("set a timer")),
            wireFormat.decode("""{"type":"input_transcript","text":"set a timer"}"""),
        )
        assertEquals(
            listOf(BackendEvent.OutputTranscript("Timer set.")),
            wireFormat.decode("""{"type":"output_transcript","text":"Timer set."}"""),
        )
    }

    @Test
    fun `transcript frames are separate deltas not cumulative utterances`() {
        val first = wireFormat.decode("""{"type":"output_transcript","text":"Timer "}""")
        val second = wireFormat.decode("""{"type":"output_transcript","text":"set."}""")
        assertEquals(listOf(BackendEvent.OutputTranscript("Timer ")), first)
        assertEquals(listOf(BackendEvent.OutputTranscript("set.")), second)
    }

    @Test
    fun `error frame carries a message, with a fallback`() {
        assertEquals(
            listOf(BackendEvent.Error("model offline")),
            wireFormat.decode("""{"type":"error","message":"model offline"}"""),
        )
        assertEquals(listOf(BackendEvent.Error("Local server error")), wireFormat.decode("""{"type":"error"}"""))
    }

    @Test
    fun `a close reason is never a message for the user — the host reports errors as frames`() {
        assertEquals(null, wireFormat.closeMessage(1011, "pipeline crashed"))
    }

    @Test
    fun `empty-text transcripts and unknown or unparseable frames yield nothing`() {
        assertTrue(wireFormat.decode("""{"type":"input_transcript","text":""}""").isEmpty())
        assertTrue(wireFormat.decode("""{"type":"nope"}""").isEmpty())
        assertTrue(wireFormat.decode("""{"no":"type"}""").isEmpty())
        assertTrue(wireFormat.decode("not json").isEmpty())
    }

    @Test
    fun `a binary frame is one chunk of audio, an empty one nothing`() {
        assertEquals(listOf(BackendEvent.Audio(byteArrayOf(1, 2, 3, 4))), wireFormat.decode(byteArrayOf(1, 2, 3, 4)))
        assertTrue(wireFormat.decode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `tool_call parses id, name and args (object or json-string)`() {
        val events = wireFormat.decode(
            """{"type":"tool_call","calls":[
                 {"id":"a","name":"portal.set_timer","args":{"duration_seconds":120}},
                 {"id":"b","name":"portal.get_time","args":"{\"zone\":\"UTC\"}"}
               ]}""",
        )
        val call = events.single() as BackendEvent.ToolCall
        assertEquals(listOf("a", "b"), call.calls.map { it.id })
        assertEquals(listOf("portal.set_timer", "portal.get_time"), call.calls.map { it.name })
        assertEquals(120, call.calls[0].args.getInt("duration_seconds"))
        assertEquals("UTC", call.calls[1].args.getString("zone"))
    }

    @Test
    fun `tool_call with missing or garbage args gets empty args`() {
        val events = wireFormat.decode(
            """{"type":"tool_call","calls":[{"id":"a","name":"x"},{"id":"b","name":"y","args":"not json"},{"id":"c","name":"z","args":5}]}""",
        )
        val call = events.single() as BackendEvent.ToolCall
        assertEquals(listOf(0, 0, 0), call.calls.map { it.args.length() })
    }

    @Test
    fun `a tool call with no name is kept, so it still gets an answer`() {
        val call = wireFormat.decode("""{"type":"tool_call","calls":[{"id":"x"}]}""").single() as BackendEvent.ToolCall
        assertEquals(listOf("x"), call.calls.map { it.id })
        assertEquals(listOf(""), call.calls.map { it.name })
    }

    @Test
    fun `tool_call with no calls yields nothing`() {
        assertTrue(wireFormat.decode("""{"type":"tool_call","calls":[]}""").isEmpty())
        assertTrue(wireFormat.decode("""{"type":"tool_call"}""").isEmpty())
    }

    @Test
    fun `tool_cancel parses ids`() {
        assertEquals(
            listOf(BackendEvent.ToolCancel(listOf("a", "b"))),
            wireFormat.decode("""{"type":"tool_cancel","ids":["a","b"]}"""),
        )
    }

    @Test
    fun `setup carries the prompt and normalized tools`() {
        val decl = JSONObject(
            """{"name":"portal.set_timer","description":"start a timer",
                "parameters":{"type":"OBJECT","properties":{"duration_seconds":{"type":"NUMBER"}},"required":["duration_seconds"]}}""",
        )
        val setup = LocalWireFormat("be helpful", listOf(decl)).setup().json()
        assertEquals("setup", setup.getString("type"))
        assertEquals("be helpful", setup.getString("systemPrompt"))
        val params = setup.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters")
        // Types are lowercased to standard JSON Schema; required (property names) is untouched.
        assertEquals("object", params.getString("type"))
        assertEquals("number", params.getJSONObject("properties").getJSONObject("duration_seconds").getString("type"))
        assertEquals("duration_seconds", params.getJSONArray("required").getString(0))
    }

    @Test
    fun `audio goes up as a raw binary frame`() {
        assertEquals(Frame.Binary(byteArrayOf(9, 8, 7)), wireFormat.audio(byteArrayOf(9, 8, 7)))
    }

    @Test
    fun `tool results and the user text turn shape the outbound frames`() {
        val result = wireFormat.toolResults(listOf(ToolResult("a", "portal.set_timer", JSONObject().put("ok", true)))).json()
        assertEquals("tool_result", result.getString("type"))
        val r0 = result.getJSONArray("results").getJSONObject(0)
        assertEquals("a", r0.getString("id"))
        assertEquals("portal.set_timer", r0.getString("name"))
        assertTrue(r0.getJSONObject("response").getBoolean("ok"))

        val text = wireFormat.userText("turn off the lights").json()
        assertEquals("user_text", text.getString("type"))
        assertEquals("turn off the lights", text.getString("text"))
    }

    @Test
    fun `its label names the local backend in errors`() {
        assertEquals("Local", wireFormat.label)
    }
}
