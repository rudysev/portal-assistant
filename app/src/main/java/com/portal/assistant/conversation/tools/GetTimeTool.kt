package com.portal.assistant.conversation.tools

import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Returns the current local date and time on the device (live, not a session-start snapshot). */
object GetTimeTool : Tool {
    override val name = "portal.get_time"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.get_time",
           "description":"Returns the current local date and time on the device.",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    // Only the formatted local datetime — NOT the raw IANA zone id (e.g. "America/Los_Angeles"), which the
    // model would read aloud verbatim. The model already has the human location (e.g. "Mountain View") in
    // its static device context for any location phrasing.
    override fun invoke(args: JSONObject): JSONObject {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        return JSONObject()
            .put("datetime", now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())))
    }
}
