package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * `portal.set_repeat`: repeat one / all / off on the session [MediaSelection.pickForRepeat] picks (only apps that
 * advertise `ACTION_SET_REPEAT_MODE`), reported as `{"repeat", "app"}` and a final turn. A bad mode, missing session
 * access, nothing playing, no app supporting repeat, or a failed call is an error and leaves the conversation open.
 */
internal class SetRepeat(private val sessions: MediaSessions, private val ownPkg: String) : Tool {

    override val name = "portal.set_repeat"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.set_repeat",
           "description":"Set repeat on the music now playing.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "mode":{"type":"STRING","description":"One of: one (repeat this song), all (repeat the album, playlist or queue) or off."}},
             "required":["mode"]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val mode = MediaSelection.repeatMode(args.optString("mode").ifBlank { null })
            ?: return ToolOutput.error("repeat must be one, all, or off")
        return sessions.onPicked(
            pick = { MediaSelection.pickForRepeat(it, ownPkg) },
            unsupported = "repeat isn't available on the current app",
        ) { index, pkg ->
            val app = sessions.appLabel(pkg)
            runCatching { sessions.setRepeat(index, mode) }.fold(
                onSuccess = {
                    DebugLog.log("media set_repeat ${mode.name.lowercase()} → $app")
                    musicCommandWorked(JSONObject().put("repeat", mode.name.lowercase()).put("app", app))
                },
                onFailure = {
                    DebugLog.log("media set_repeat failed for $pkg: ${it.message}")
                    ToolOutput.error("couldn't set repeat on $app: ${it.message}")
                },
            )
        }
    }
}
