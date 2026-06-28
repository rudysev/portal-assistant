package com.portal.assistant.conversation.tools

import org.json.JSONObject
import kotlin.math.roundToInt

/** Sets the speaker volume to an absolute level. */
class SetVolumeTool(private val volume: VolumeController) : Tool {

    override val name = "portal.set_volume"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_volume",
           "description":"Set the speaker volume to an absolute level (0-100). Use 100 for 'max'/'full volume' and 0 for 'volume to zero'; for 'mute'/'unmute' use portal.set_mute instead.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "level_percent":{"type":"NUMBER","description":"Target volume, 0 to 100."}},
             "required":["level_percent"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        // optDouble + round so a float (e.g. 49.5) isn't truncated; out-of-range is clamped by VolumeMath.
        val percent = args.optDouble("level_percent", Double.NaN)
        if (percent.isNaN()) return JSONObject().put("error", "level_percent required")
        return volume.setPercent(percent.roundToInt()).toJson()
    }
}
