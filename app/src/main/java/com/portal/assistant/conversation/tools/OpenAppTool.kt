package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Opens an installed app by name. */
class OpenAppTool(private val launcher: AppLauncher) : Tool {

    override val name = "portal.open_app"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.open_app",
           "description":"Open an installed app by name. If there's no confident match, the response lists close app names in 'candidates': offer those and ask, don't guess. To play a specific song, artist or album use portal.play_music instead.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "name":{"type":"STRING","description":"The app to open, as the user said it (e.g. 'Spotify', 'the calendar')."}},
             "required":["name"]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return ToolOutput.error("name required")
        return ToolOutput(launcher.open(name))
    }
}
