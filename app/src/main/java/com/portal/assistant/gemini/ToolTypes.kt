package com.portal.assistant.gemini

import org.json.JSONObject

/** A function call request from the model during a Live session. */
data class FunctionCall(val id: String, val name: String, val args: JSONObject)

/** A function call result ready to send back to the model. */
data class ToolResult(val id: String, val name: String, val response: JSONObject)
