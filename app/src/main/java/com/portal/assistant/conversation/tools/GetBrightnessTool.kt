package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Reports the current display brightness. */
class GetBrightnessTool(private val brightness: BrightnessController) : Tool {

    override val name = "portal.get_brightness"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.get_brightness",
           "description":"Get the current display brightness (0-100%).",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject = brightness.current().toJson()
}
