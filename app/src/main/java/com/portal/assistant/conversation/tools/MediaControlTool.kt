package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Controls media playback (play / pause / next / previous) on the active music app. */
class MediaControlTool(private val media: MediaControl) : Tool {

    override val name = "portal.media_control"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.media_control",
           "description":"Control music/media playback on the device: play (resume), pause, skip to next or previous track.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "action":{"type":"STRING","description":"One of: play, pause, next, previous."}},
             "required":["action"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        val action = when (args.optString("action").trim().lowercase()) {
            "play", "resume" -> MediaAction.PLAY
            "pause", "stop" -> MediaAction.PAUSE
            "next", "skip" -> MediaAction.NEXT
            "previous", "back" -> MediaAction.PREVIOUS
            else -> return JSONObject().put("error", "action must be play, pause, next, or previous")
        }
        return media.control(action)
    }
}
