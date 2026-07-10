package com.portal.assistant.conversation.backend.local

import com.portal.assistant.conversation.ToolResult
import com.portal.assistant.conversation.backend.local.LocalBackend.ServerEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the local backend's wire boundary: [LocalBackend.parseServerMessage] (inbound JSON → neutral
 * [ServerEvent]s) and the outbound builders. Pure, so no Android/Robolectric — the socket plumbing and
 * the [ServerEvent] → [VoiceBackend.Listener] relay are mechanical (see the seam contract in
 * `VoiceBackendTest`).
 */
class LocalBackendTest {

    @Test
    fun `parses each control frame to its event`() {
        assertEquals(listOf(ServerEvent.Ready), LocalBackend.parseServerMessage("""{"type":"ready"}"""))
        assertEquals(listOf(ServerEvent.ModelGenerating), LocalBackend.parseServerMessage("""{"type":"model_generating"}"""))
        assertEquals(listOf(ServerEvent.TurnComplete), LocalBackend.parseServerMessage("""{"type":"turn_complete"}"""))
        assertEquals(listOf(ServerEvent.Interrupted), LocalBackend.parseServerMessage("""{"type":"interrupted"}"""))
        assertEquals(
            listOf(ServerEvent.InputTranscript("set a timer")),
            LocalBackend.parseServerMessage("""{"type":"input_transcript","text":"set a timer"}"""),
        )
        assertEquals(
            listOf(ServerEvent.OutputTranscript("Timer set.")),
            LocalBackend.parseServerMessage("""{"type":"output_transcript","text":"Timer set."}"""),
        )
    }

    @Test
    fun `transcript frames are separate deltas not cumulative utterances`() {
        val first = LocalBackend.parseServerMessage("""{"type":"output_transcript","text":"Timer "}""")
        val second = LocalBackend.parseServerMessage("""{"type":"output_transcript","text":"set."}""")
        assertEquals(listOf(ServerEvent.OutputTranscript("Timer ")), first)
        assertEquals(listOf(ServerEvent.OutputTranscript("set.")), second)
    }

    @Test
    fun `error frame carries a message, with a fallback`() {
        assertEquals(
            listOf(ServerEvent.Error("model offline")),
            LocalBackend.parseServerMessage("""{"type":"error","message":"model offline"}"""),
        )
        assertEquals(
            listOf(ServerEvent.Error("Local server error")),
            LocalBackend.parseServerMessage("""{"type":"error"}"""),
        )
    }

    @Test
    fun `empty-text transcripts and unknown or unparseable frames yield nothing`() {
        assertTrue(LocalBackend.parseServerMessage("""{"type":"input_transcript","text":""}""").isEmpty())
        assertTrue(LocalBackend.parseServerMessage("""{"type":"nope"}""").isEmpty())
        assertTrue(LocalBackend.parseServerMessage("""{"no":"type"}""").isEmpty())
        assertTrue(LocalBackend.parseServerMessage("not json").isEmpty())
    }

    @Test
    fun `tool_call parses id, name and args (object or json-string)`() {
        val events = LocalBackend.parseServerMessage(
            """{"type":"tool_call","calls":[
                 {"id":"a","name":"portal.set_timer","args":{"duration_seconds":120}},
                 {"id":"b","name":"portal.get_time","args":"{}"}
               ]}""",
        )
        val call = events.single() as ServerEvent.ToolCall
        assertEquals(listOf("a", "b"), call.calls.map { it.id })
        assertEquals(listOf("portal.set_timer", "portal.get_time"), call.calls.map { it.name })
        assertEquals(120, call.calls[0].args.getInt("duration_seconds"))
    }

    @Test
    fun `tool_call with no valid calls yields nothing`() {
        assertTrue(LocalBackend.parseServerMessage("""{"type":"tool_call","calls":[]}""").isEmpty())
        assertTrue(LocalBackend.parseServerMessage("""{"type":"tool_call","calls":[{"id":"x"}]}""").isEmpty())
    }

    @Test
    fun `tool_cancel parses ids`() {
        val events = LocalBackend.parseServerMessage("""{"type":"tool_cancel","ids":["a","b"]}""")
        assertEquals(listOf(ServerEvent.ToolCancel(listOf("a", "b"))), events)
    }

    @Test
    fun `buildSetup carries the prompt and normalized tools`() {
        val decl = JSONObject(
            """{"name":"portal.set_timer","description":"start a timer",
                "parameters":{"type":"OBJECT","properties":{"duration_seconds":{"type":"NUMBER"}},"required":["duration_seconds"]}}""",
        )
        val setup = JSONObject(LocalBackend.buildSetup("be helpful", listOf(decl)))
        assertEquals("setup", setup.getString("type"))
        assertEquals("be helpful", setup.getString("systemPrompt"))
        val params = setup.getJSONArray("tools").getJSONObject(0).getJSONObject("parameters")
        // Types are lowercased to standard JSON Schema; required (property names) is untouched.
        assertEquals("object", params.getString("type"))
        assertEquals("number", params.getJSONObject("properties").getJSONObject("duration_seconds").getString("type"))
        assertEquals("duration_seconds", params.getJSONArray("required").getString(0))
    }

    @Test
    fun `buildToolResult and buildUserText shape the outbound frames`() {
        val result = JSONObject(
            LocalBackend.buildToolResult(listOf(ToolResult("a", "portal.set_timer", JSONObject().put("ok", true)))),
        )
        assertEquals("tool_result", result.getString("type"))
        val r0 = result.getJSONArray("results").getJSONObject(0)
        assertEquals("a", r0.getString("id"))
        assertEquals("portal.set_timer", r0.getString("name"))
        assertTrue(r0.getJSONObject("response").getBoolean("ok"))

        val text = JSONObject(LocalBackend.buildUserText("turn off the lights"))
        assertEquals("user_text", text.getString("type"))
        assertEquals("turn off the lights", text.getString("text"))
    }

    @Test
    fun `parseWssUrl canonicalizes LAN host port via LocalVoiceHost`() {
        assertEquals("wss://192.168.1.5:8080", LocalBackend.parseWssUrl("192.168.1.5:8080"))
        assertEquals("wss://raspberrypi:8080", LocalBackend.parseWssUrl("wss://raspberrypi:8080"))
    }

    @Test
    fun `parseWssUrl rejects insecure schemes and non-LAN hosts`() {
        assertNull(LocalBackend.parseWssUrl("ws://192.168.1.5:8080"))
        assertNull(LocalBackend.parseWssUrl("http://192.168.1.5:8080"))
        assertNull(LocalBackend.parseWssUrl("https://192.168.1.5:8080"))
        assertNull(LocalBackend.parseWssUrl("8.8.8.8:8080"))
    }
}
