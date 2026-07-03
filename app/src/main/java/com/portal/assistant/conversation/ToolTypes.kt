package com.portal.assistant.conversation

import org.json.JSONObject

/**
 * Model-neutral tool-call types (just id, name, and JSON args/response) shared by the reducer, the
 * engine, and any [backend][com.portal.assistant.conversation.backend.VoiceBackend] — so the pure state
 * machine can name them without depending on the model behind the seam.
 */

/** A function call request from the model during a session. */
data class FunctionCall(val id: String, val name: String, val args: JSONObject)

/** A function call result ready to send back to the model. */
data class ToolResult(val id: String, val name: String, val response: JSONObject)
