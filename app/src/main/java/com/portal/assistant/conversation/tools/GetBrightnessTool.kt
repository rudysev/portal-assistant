package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Reports the current display brightness. */
class GetBrightnessTool(private val brightness: BrightnessController) : Tool {

    override val name = "portal.get_brightness"

    override val declaration: JSONObject = JSONObject(DECLARATION)

    override fun invoke(args: JSONObject): ToolOutput = ToolOutput(brightness.current().toJson())

    companion object {
        /** The declared JSON, readable without the `Context` this tool's collaborator needs. */
        const val DECLARATION =
            """{"name":"portal.get_brightness",
               "description":"Get the current display brightness (0-100%).",
               "parameters":{"type":"OBJECT","properties":{},"required":[]}}"""
    }
}
