package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Turns Do Not Disturb on or off. */
class SetDoNotDisturbTool(private val dnd: DoNotDisturbController) : Tool {

    override val name = "portal.set_do_not_disturb"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_do_not_disturb",
           "description":"Turn Do Not Disturb on or off. It stays in that state until changed again.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "enabled":{"type":"BOOLEAN","description":"true to turn Do Not Disturb on, false to turn it off."}},
             "required":["enabled"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        if (!args.has("enabled")) return JSONObject().put("error", "enabled required")
        return dnd.setEnabled(args.optBoolean("enabled"))
    }
}
