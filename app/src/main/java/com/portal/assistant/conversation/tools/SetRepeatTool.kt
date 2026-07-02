package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Sets the repeat mode on the active music app's session ([MediaControl.setRepeat]). Only apps that
 * advertise `ACTION_SET_REPEAT_MODE` are driven; others return a graceful error.
 */
class SetRepeatTool(private val media: MediaControl) : Tool {

    override val name = "portal.set_repeat"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_repeat",
           "description":"Set repeat on the currently-playing music: 'one' repeats the current song, 'all' repeats the album/playlist/queue, 'off' turns repeat off.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "mode":{"type":"STRING","description":"One of: one (repeat this song), all (repeat the album/playlist), off."}},
             "required":["mode"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject = media.setRepeat(args.optString("mode").ifBlank { null }) // setRepeat owns the mode check
}
