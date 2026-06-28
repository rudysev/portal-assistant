package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Reports the current speaker volume and mute state. */
class GetVolumeTool(private val volume: VolumeController) : Tool {

    override val name = "portal.get_volume"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.get_volume",
           "description":"Get the current speaker volume (0-100%) and whether it is muted.",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject = volume.current().toJson()
}
