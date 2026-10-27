package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** A single on-device capability the model can invoke via function calling. */
interface Tool {
    /** Gemini function name (namespaced, e.g. "portal.get_time"). */
    val name: String

    /** Gemini function declaration (OpenAPI subset) sent in the session setup. */
    val declaration: JSONObject

    /**
     * Synchronous invocation: the response for the model and, for a **final turn**, its instruction ([ToolOutput]).
     * Throw on error — the engine wraps exceptions in {"error":"…"}.
     */
    fun invoke(args: JSONObject): ToolOutput
}
