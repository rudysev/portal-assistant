package com.portal.assistant.conversation.tools.music

import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * `portal.media_control`: play / pause / next / previous on the session [MediaSelection.pickForControl] picks,
 * reported as `{"action", "app"}` and a final turn; an unknown action, missing session access, nothing playing or no
 * session supporting the action is an error and leaves the conversation open.
 *
 * Makes "pause" / "stop" outlast the **hush** (ADR 0001): while [hush] is held the music is already quiet, so a pause
 * first asks the hush to keep it paused after the lift (a focus escalation, never a session `pause()` first). Granted,
 * it reports `{"action": "pause"}` with no session call (no session access needed) and is a final turn; denied or with
 * no hush held, the pause goes to the session.
 */
internal class MediaControl(private val sessions: MediaSessions, private val hush: Hush, private val ownPkg: String) :
    Tool {

    override val name = "portal.media_control"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.media_control",
           "description":"Control music/media playback on the device: play (resume), pause, skip to next or previous track.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "action":{"type":"STRING","description":"One of: play, pause, next, previous."}},
             "required":["action"]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val action = MediaAction.parse(args.optString("action"))
            ?: return ToolOutput.error("action must be play, pause, next, or previous")
        val keptPaused = action == MediaAction.PAUSE && hush.keepPaused()
        return if (keptPaused) {
            musicCommandWorked(JSONObject().put("action", "pause"))
        } else {
            sessions.onPicked(
                pick = { MediaSelection.pickForControl(it, action, ownPkg) },
                unsupported = "no media session supports that action",
            ) { index, pkg ->
                sessions.transport(index, action)
                val app = sessions.appLabel(pkg)
                DebugLog.log("media ${action.name.lowercase()} → $app")
                musicCommandWorked(JSONObject().put("action", action.name.lowercase()).put("app", app))
            }
        }
    }
}
