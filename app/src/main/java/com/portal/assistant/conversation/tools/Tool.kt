package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** A single on-device capability the model can invoke via function calling. */
interface Tool {
    /** Gemini function name (namespaced, e.g. "portal.get_time"). */
    val name: String

    /** Gemini function declaration (OpenAPI subset) sent in the session setup. */
    val declaration: JSONObject

    /** Synchronous invocation. Throw on error — the engine wraps exceptions in {"error":"…"}. */
    fun invoke(args: JSONObject): JSONObject
}
