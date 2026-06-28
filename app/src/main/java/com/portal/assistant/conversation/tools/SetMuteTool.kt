package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Mutes or unmutes the speaker (real mute — unmute restores the prior level). */
class SetMuteTool(private val volume: VolumeController) : Tool {

    override val name = "portal.set_mute"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_mute",
           "description":"Mute or unmute the speaker. Use this for 'mute'/'unmute'/'be quiet'; unmute restores the previous level.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "muted":{"type":"BOOLEAN","description":"true to mute, false to unmute."}},
             "required":["muted"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        if (!args.has("muted")) return JSONObject().put("error", "muted required")
        return volume.setMuted(args.optBoolean("muted")).toJson()
    }
}
