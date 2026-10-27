package com.portal.assistant.conversation.tools.music

import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import org.json.JSONObject

/**
 * **Music**: the music tools behind the `Tool` seam. Picks the session or app a command targets, builds every result
 * the model reads, decides which **music command** makes a **final turn**, and keeps the conversation's [hush]
 * invisible to the model (ADR 0001). Reaches the device only through [sessions] and [apps]; [ownPkg] (our own session)
 * is never targeted. Built per conversation by `ToolRegistry`; its tools run on the tool-executor thread.
 */
class Music(sessions: MediaSessions, apps: MusicApps, hush: Hush, ownPkg: String) {

    /** `portal.now_playing`: the playing (or paused) track, never a final turn. */
    val nowPlaying: Tool = NowPlaying(sessions, hush, ownPkg)

    /** `portal.play_music`: starts music by name; final when it started, not when the app was only opened. */
    val playMusic: Tool = PlayMusic(apps)
}

/** What a tool says when session access isn't granted. */
internal fun sessionAccessError(): JSONObject =
    JSONObject().put("error", "media control isn't enabled — grant notification access in Settings")

/** A **music command** that worked: a **final turn**, with the instruction that tells the model so. */
internal fun musicCommandWorked(response: JSONObject): ToolOutput = ToolOutput(response, MUSIC_COMMAND_FINAL_TURN)

private const val MUSIC_COMMAND_FINAL_TURN = "The conversation ends after your reply: briefly confirm, answer " +
    "anything else the user asked, and don't ask a follow-up question."
