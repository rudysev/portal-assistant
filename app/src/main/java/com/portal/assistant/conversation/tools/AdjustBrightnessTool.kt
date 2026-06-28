package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Nudges the display brightness up or down by one step. */
class AdjustBrightnessTool(private val brightness: BrightnessController) : Tool {

    override val name = "portal.adjust_brightness"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.adjust_brightness",
           "description":"Raise or lower the display brightness by one step of about 15% (for 'brighter'/'dimmer'/'turn it up/down'). For a specific level use portal.set_brightness.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "direction":{"type":"STRING","description":"'up' or 'down'."}},
             "required":["direction"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        val up = when (args.optString("direction").trim().lowercase()) {
            "up", "brighter", "brighten", "raise" -> true
            "down", "dimmer", "dim", "lower", "darker" -> false
            else -> return JSONObject().put("error", "direction must be up or down")
        }
        return brightness.adjust(up)
    }
}
