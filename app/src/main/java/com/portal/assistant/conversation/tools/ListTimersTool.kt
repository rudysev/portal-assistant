package com.portal.assistant.conversation.tools

import org.json.JSONArray
import org.json.JSONObject

/** Lists the active timers and how long is left on each. */
class ListTimersTool(private val scheduler: TimerScheduler) : Tool {

    override val name = "portal.list_timers"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.list_timers",
           "description":"List active timers with their remaining time. Use it when the user asks how much time is left; don't guess time left from the portal.set_timer response.",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
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
        return ToolOutput(JSONObject().put("timers", arr))
    }
}
