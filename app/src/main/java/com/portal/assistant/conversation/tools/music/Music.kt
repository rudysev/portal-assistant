package com.portal.assistant.conversation.tools.music

import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.tools.Tool
import org.json.JSONObject

/**
 * **Music**: the music tools behind the `Tool` seam. Picks the session a command targets, builds every result the
 * model reads, and keeps the conversation's [hush] invisible to it (ADR 0001). Reaches the device only through
 * [sessions]; [ownPkg] (our own session) is never targeted. Built per conversation by `ToolRegistry`; its tools run
 * on the tool-executor thread.
 */
class Music(sessions: MediaSessions, hush: Hush, ownPkg: String) {

    /** `portal.now_playing`: the playing (or paused) track, never a final turn. */
    val nowPlaying: Tool = NowPlaying(sessions, hush, ownPkg)
}

/** What a tool says when session access isn't granted. */
internal fun sessionAccessError(): JSONObject =
    JSONObject().put("error", "media control isn't enabled — grant notification access in Settings")
