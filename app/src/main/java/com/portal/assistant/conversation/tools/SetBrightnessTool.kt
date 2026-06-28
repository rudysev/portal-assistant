package com.portal.assistant.conversation.tools

import org.json.JSONObject
import kotlin.math.roundToInt

/** Sets the display brightness to an absolute level. */
class SetBrightnessTool(private val brightness: BrightnessController) : Tool {

    override val name = "portal.set_brightness"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_brightness",
           "description":"Set the display brightness to an absolute level (0-100). Use 100 for 'max'/'full brightness'. 0 is the minimum visible level, not off (the screen never goes fully dark).",
           "parameters":{"type":"OBJECT",
             "properties":{
               "level_percent":{"type":"NUMBER","description":"Target brightness, 0 to 100."}},
             "required":["level_percent"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        // optDouble + round so a float (e.g. 49.5) isn't truncated; out-of-range is clamped by BrightnessMath.
        val percent = args.optDouble("level_percent", Double.NaN)
        if (percent.isNaN()) return JSONObject().put("error", "level_percent required")
        return brightness.setPercent(percent.roundToInt())
    }
}
