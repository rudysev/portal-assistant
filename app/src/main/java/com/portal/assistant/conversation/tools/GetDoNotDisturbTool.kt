package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Reports whether Do Not Disturb is on. */
class GetDoNotDisturbTool(private val dnd: DoNotDisturbController) : Tool {

    override val name = "portal.get_do_not_disturb"

    override val declaration: JSONObject = JSONObject(DECLARATION)

    override fun invoke(args: JSONObject): ToolOutput = ToolOutput(dnd.current())

    companion object {
        /** The declared JSON, readable without the `Context` this tool's collaborator needs. */
        const val DECLARATION =
            """{"name":"portal.get_do_not_disturb",
               "description":"Get whether Do Not Disturb is currently on.",
               "parameters":{"type":"OBJECT","properties":{},"required":[]}}"""
    }
}
