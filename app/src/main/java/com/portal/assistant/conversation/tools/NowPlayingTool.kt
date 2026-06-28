package com.portal.assistant.conversation.tools

import org.json.JSONObject

/** Reports what music/media is currently playing (title, artist, app). */
class NowPlayingTool(private val media: MediaControl) : Tool {

    override val name = "portal.now_playing"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.now_playing",
           "description":"Get the currently playing (or paused) track: title, artist, and app.",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject = media.nowPlaying()
}
