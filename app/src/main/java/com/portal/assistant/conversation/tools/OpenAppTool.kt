package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Opens an installed app by name. */
class OpenAppTool(private val launcher: AppLauncher) : Tool {

    override val name = "portal.open_app"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.open_app",
           "description":"Open (launch) an installed app's screen by name, e.g. 'open Spotify' or 'open the calendar'. On no confident match the response has a 'candidates' list of close app names — offer those by name and ask, do not guess. To play a specific song, artist, or album use portal.play_music instead; this only opens the app.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "name":{"type":"STRING","description":"The app to open, as the user said it (e.g. 'Spotify', 'the calendar')."}},
             "required":["name"]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return JSONObject().put("error", "name required")
        return launcher.open(name)
    }
}
