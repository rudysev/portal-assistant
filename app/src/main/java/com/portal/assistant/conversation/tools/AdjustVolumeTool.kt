package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Nudges the speaker volume up or down by one device step. */
class AdjustVolumeTool(private val volume: VolumeController) : Tool {

    override val name = "portal.adjust_volume"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.adjust_volume",
           "description":"Raise or lower the speaker volume by one step (for 'louder'/'quieter'/'turn it up/down'). For a specific level use portal.set_volume.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "direction":{"type":"STRING","description":"'up' or 'down'."}},
             "required":["direction"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        val raise = when (args.optString("direction").trim().lowercase()) {
            "up", "louder", "raise" -> true
            "down", "quieter", "lower" -> false
            else -> return JSONObject().put("error", "direction must be up or down")
        }
        return volume.adjust(raise).toJson()
    }
}
