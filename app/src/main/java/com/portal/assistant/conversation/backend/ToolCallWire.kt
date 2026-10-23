package com.portal.assistant.conversation.backend

import com.portal.assistant.conversation.FunctionCall
import com.portal.assistant.conversation.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool-call wire helpers shared by every [WireFormat]: both backends frame a call as `{id, name, args}`, a
 * cancellation as a list of call ids and a result as `{id, name, response}`. Pure. A call with an empty name is **kept**: it is answered through the Tool
 * runner's unknown-tool error, so every call the backend makes gets one answer.
 */
object ToolCallWire {

    /** The calls in [elements] (non-object entries skipped); null → none. */
    fun parseCalls(elements: JSONArray?): List<FunctionCall> {
        if (elements == null) return emptyList()
        return (0 until elements.length()).mapNotNull { i -> elements.optJSONObject(i)?.let(::parseCall) }
    }

    /** One `{id, name, args}` element; `args` may be an object or a JSON string, anything else → empty args. */
    fun parseCall(element: JSONObject): FunctionCall {
        // `Any?`, not the platform type `Any!`: Kotlin 2.4.21 wrongly reports a when over `Any!` as non-exhaustive
        // despite the `else`.
        val args = when (val rawArgs: Any? = element.opt("args")) {
            is JSONObject -> rawArgs
            is String -> runCatching { JSONObject(rawArgs) }.getOrElse { JSONObject() }
            else -> JSONObject()
        }
        return FunctionCall(element.optString("id"), element.optString("name"), args)
    }

    /** The non-empty ids of a tool-call cancellation; null → none. */
    fun parseCancelIds(idElements: JSONArray?): List<String> {
        if (idElements == null) return emptyList()
        return (0 until idElements.length()).mapNotNull { i -> idElements.optString(i).takeIf { it.isNotEmpty() } }
    }

    /** One `{id, name, response}` result element. */
    fun resultElement(result: ToolResult): JSONObject = JSONObject().put("id", result.id).put("name", result.name).put("response", result.response)
}
