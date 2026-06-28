package com.portal.assistant.conversation.tools

import org.json.JSONArray
import org.json.JSONObject

/** Lists the active timers and how long is left on each. */
class ListTimersTool(private val scheduler: TimerScheduler) : Tool {

    override val name = "portal.list_timers"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.list_timers",
           "description":"List active timers with their remaining time. Use this when the user asks how much time is left.",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        val timers = scheduler.list()
        val now = System.currentTimeMillis() // after list() so remaining isn't skewed by compaction's clock tick
        val arr = JSONArray()
        timers.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("label", t.label)
                    .put("remaining_seconds", Timers.remainingSec(t, now)),
            )
        }
        return JSONObject().put("timers", arr)
    }
}
