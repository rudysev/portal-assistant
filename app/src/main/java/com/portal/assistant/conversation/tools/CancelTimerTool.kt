package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Cancels an active timer by id or by name. */
class CancelTimerTool(private val scheduler: TimerScheduler) : Tool {

    override val name = "portal.cancel_timer"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.cancel_timer",
           "description":"Cancel an active timer. Provide id or label (call portal.list_timers first if unsure).",
           "parameters":{"type":"OBJECT",
             "properties":{
               "id":{"type":"NUMBER","description":"The timer id from portal.list_timers."},
               "label":{"type":"STRING","description":"The timer name, e.g. 'pasta'."}},
             "required":[]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        val id = if (args.has("id")) args.optInt("id") else null
        val label = args.optString("label").ifBlank { null }
        if (id == null && label == null) return JSONObject().put("error", "provide id or label")
        val cancelled = scheduler.cancel(id, label) ?: return JSONObject().put("error", "no matching timer")
        return JSONObject()
            .put("cancelled", true)
            .put("id", cancelled.id)
            .put("label", cancelled.label)
    }
}
