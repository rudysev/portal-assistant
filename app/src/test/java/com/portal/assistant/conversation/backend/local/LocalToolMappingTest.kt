package com.portal.assistant.conversation.backend.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The Gemini-shaped → standard-JSON-Schema normalization, including the tricky "a property named type" case. */
class LocalToolMappingTest {

    @Test
    fun `lowercases schema types recursively but keeps property names and required`() {
        // play_music-style: a *property* literally named "type" whose own schema type must still lowercase.
        val decl = JSONObject(
            """{"name":"portal.play_music","description":"play music",
                "parameters":{"type":"OBJECT","properties":{
                    "query":{"type":"STRING"},
                    "type":{"type":"STRING","description":"song|artist|album"}
                },"required":["query"]}}""",
        )

        val out = LocalToolMapping.normalizeDeclaration(decl)
        val params = out.getJSONObject("parameters")

        assertEquals("portal.play_music", out.getString("name"))
        assertEquals("play music", out.getString("description"))
        assertEquals("object", params.getString("type"))

        val props = params.getJSONObject("properties")
        assertEquals("string", props.getJSONObject("query").getString("type"))
        // The property KEY "type" is preserved; its nested schema type is lowercased.
        assertEquals("string", props.getJSONObject("type").getString("type"))
        assertEquals("song|artist|album", props.getJSONObject("type").getString("description"))

        // required lists property names — never lowercased/altered.
        assertEquals("query", params.getJSONArray("required").getString(0))
    }

    @Test
    fun `drops fields absent on the source and handles a no-parameter tool`() {
        val decl = JSONObject("""{"name":"portal.get_time","description":"current time"}""")
        val out = LocalToolMapping.normalizeDeclaration(decl)
        assertEquals("portal.get_time", out.getString("name"))
        assertFalse("no parameters key when the source has none", out.has("parameters"))
    }

    @Test
    fun `normalize maps a whole batch`() {
        val batch = listOf(
            JSONObject("""{"name":"a","parameters":{"type":"OBJECT"}}"""),
            JSONObject("""{"name":"b","parameters":{"type":"STRING"}}"""),
        )
        val out = LocalToolMapping.normalize(batch)
        assertEquals(2, out.size)
        assertEquals("object", out[0].getJSONObject("parameters").getString("type"))
        assertEquals("string", out[1].getJSONObject("parameters").getString("type"))
    }
}
