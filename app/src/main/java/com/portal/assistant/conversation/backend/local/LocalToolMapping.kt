package com.portal.assistant.conversation.backend.local

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, unit-tested shim that normalizes the app's tool declarations for the local backend's wire.
 *
 * The built-in [Tool][com.portal.assistant.conversation.tools.Tool] declarations use Gemini's
 * JSON-Schema spelling — uppercase types (`"OBJECT"`, `"STRING"`, `"NUMBER"`). Standard JSON Schema
 * (what non-Gemini models expect) uses lowercase (`"object"`, `"string"`, `"number"`). We normalize to
 * **standard JSON Schema** here and keep the neutral `{name, description, parameters}` shape — the host
 * does any vendor-specific wrapping (e.g. OpenAI's `{"type":"function","function":{…}}` for Ollama), so
 * our LAN wire stays vendor-agnostic.
 *
 * Only the value of a schema keyword named `type` is lowercased (a string, or each string in a type
 * array); a *property* literally named `type` (e.g. `play_music`'s `type` hint) keeps its key and its
 * nested schema is normalized recursively. `required` (property names) is never touched.
 */
object LocalToolMapping {

    /** Normalize a batch of declarations (built-in + external) for the wire. */
    fun normalize(declarations: List<JSONObject>): List<JSONObject> = declarations.map(::normalizeDeclaration)

    /** One declaration → a copy in standard JSON Schema with the neutral `{name, description, parameters}` shape. */
    fun normalizeDeclaration(decl: JSONObject): JSONObject {
        val out = JSONObject()
        decl.optString("name").takeIf { it.isNotEmpty() }?.let { out.put("name", it) }
        decl.optString("description").takeIf { it.isNotEmpty() }?.let { out.put("description", it) }
        decl.optJSONObject("parameters")?.let { out.put("parameters", normalizeObject(it)) }
        return out
    }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        is JSONObject -> normalizeObject(value)
        is JSONArray -> normalizeArray(value)
        else -> value
    }

    private fun normalizeObject(obj: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in obj.keys()) {
            val v = obj.get(key)
            out.put(key, if (key == "type") lowerType(v) else normalizeValue(v))
        }
        return out
    }

    private fun normalizeArray(arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) out.put(normalizeValue(arr.get(i)))
        return out
    }

    /** The value of a `type` keyword: a single type name, or an array of them. Non-strings pass through. */
    private fun lowerType(value: Any?): Any? = when (value) {
        is String -> value.lowercase()

        is JSONArray -> JSONArray().apply {
            for (i in 0 until value.length()) put((value.get(i) as? String)?.lowercase() ?: value.get(i))
        }

        else -> normalizeValue(value)
    }
}
