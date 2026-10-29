package com.portal.assistant.conversation.tools

import org.json.JSONObject
import kotlin.math.roundToInt

/** Sets the display brightness to an absolute level. */
class SetBrightnessTool(private val brightness: BrightnessController) : Tool {

    override val name = "portal.set_brightness"

    override val declaration: JSONObject = JSONObject(DECLARATION)

    override fun invoke(args: JSONObject): ToolOutput {
        // optDouble + round so a float (e.g. 49.5) isn't truncated; out-of-range is clamped by BrightnessMath.
        val percent = args.optDouble("level_percent", Double.NaN)
        if (percent.isNaN()) return ToolOutput.error("level_percent required")
        return ToolOutput(brightness.setPercent(percent.roundToInt()))
    }

    companion object {
        /** The declared JSON, readable without the `Context` this tool's collaborator needs. */
        const val DECLARATION =
            """{"name":"portal.set_brightness",
               "description":"Set the display brightness to a level. Use 100 for 'max'/'full brightness'; 0 is the dimmest visible level, not off.",
               "parameters":{"type":"OBJECT",
                 "properties":{
                   "level_percent":{"type":"NUMBER","description":"Target brightness, 0 to 100."}},
                 "required":["level_percent"]}}"""
    }
}
