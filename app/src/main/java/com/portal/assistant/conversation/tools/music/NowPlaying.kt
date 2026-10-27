package com.portal.assistant.conversation.tools.music

import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import com.portal.commons.DebugLog
import org.json.JSONObject

/**
 * `portal.now_playing`: the session [MediaSelection.pickForStatus] picks, as `playing`, `app`, and `title` / `artist`
 * when known; `{"playing": false}` when none is found. Never a final turn: it expects an answer.
 *
 * Keeps the **hush** invisible: while [hush] is silencing playback, a found track is paused only because Jarvis is
 * listening, so it reports `"playing": true` with `"hushed": true`. A `hushed` flag beside `"playing": false` wasn't
 * enough: on a gen2 the model still called the track paused. A track already paused when the hush started, or a kept
 * pause, reads as paused.
 */
internal class NowPlaying(private val sessions: MediaSessions, private val hush: Hush, private val ownPkg: String) :
    Tool {

    override val name = "portal.now_playing"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.now_playing",
           "description":"Get the currently playing (or paused) track: title, artist, and app. hushed=true means the track is playing and only quiet while you listen.",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val active = sessions.active() ?: return ToolOutput(sessionAccessError())
        val index = MediaSelection.pickForStatus(active.map { it.info }, ownPkg)
        return ToolOutput(if (index == null) JSONObject().put("playing", false) else report(active[index]))
    }

    private fun report(session: ActiveSession): JSONObject {
        val playing = session.info.isPlaying
        val out = JSONObject()
            .put("playing", playing)
            .put("app", sessions.appLabel(session.info.pkg))
        session.title?.let { out.put("title", it) } // omit when unknown rather than send blank
        session.artist?.let { out.put("artist", it) }
        DebugLog.log("media now_playing → ${session.info.pkg} playing=$playing")
        if (hush.isSilencingPlayback) out.put("playing", true).put("hushed", true)
        return out
    }
}
