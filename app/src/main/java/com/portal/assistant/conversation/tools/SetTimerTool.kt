package com.portal.assistant.conversation.tools

import org.json.JSONObject
import kotlin.math.roundToInt

/** Starts a countdown timer that fires an alarm notification when it elapses. */
class SetTimerTool(private val scheduler: TimerScheduler) : Tool {

    override val name = "portal.set_timer"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_timer",
           "description":"Start a countdown timer that alarms when it elapses. Convert the user's phrasing into duration_seconds.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "duration_seconds":{"type":"NUMBER","description":"Countdown length in seconds (1 to 86400)."},
               "label":{"type":"STRING","description":"Optional name, e.g. 'pasta', so it can be listed or cancelled by name."}},
             "required":["duration_seconds"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        // optDouble + round so a float duration (e.g. 30.7) isn't silently truncated by optInt.
        val duration = args.optDouble("duration_seconds", Double.NaN)
        val seconds = if (duration.isNaN()) -1 else duration.roundToInt()
        val label = args.optString("label")
        val entry = scheduler.set(seconds, label) // throws on out-of-range → engine wraps as {"error":…}
        return JSONObject()
            .put("id", entry.id)
            .put("label", entry.label)
            .put("duration_seconds", entry.durationSec)
    }
}
