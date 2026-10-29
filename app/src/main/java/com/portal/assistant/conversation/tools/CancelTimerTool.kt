package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Cancels an active timer by id or by name. */
class CancelTimerTool(private val scheduler: TimerScheduler) : Tool {

    override val name = "portal.cancel_timer"

    override val declaration: JSONObject = JSONObject(DECLARATION)

    override fun invoke(args: JSONObject): ToolOutput {
        val id = if (args.has("id")) args.optInt("id") else null
        val label = args.optString("label").ifBlank { null }
        if (id == null && label == null) return ToolOutput.error("provide id or label")
        val cancelled = scheduler.cancel(id, label)
        return ToolOutput(
            cancelled?.let { JSONObject().put("cancelled", true).put("id", it.id).put("label", it.label) }
                ?: JSONObject().put("error", "no matching timer"),
        )
    }

    companion object {
        /** The declared JSON, readable without the `Context` this tool's collaborator needs. */
        const val DECLARATION =
            """{"name":"portal.cancel_timer",
               "description":"Cancel an active timer by id or label.",
               "parameters":{"type":"OBJECT",
                 "properties":{
                   "id":{"type":"NUMBER","description":"The timer id from portal.list_timers (call it first if unsure)."},
                   "label":{"type":"STRING","description":"The timer name, e.g. 'pasta'."}},
                 "required":[]}}"""
    }
}
