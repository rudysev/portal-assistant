package com.portal.assistant.conversation.tools

import com.portal.assistant.audio.Hush
import org.json.JSONObject

/**
 * Makes "pause" / "stop" outlast the **hush**: while [hush] is held, the music is already quiet, so a pause asks the
 * hush to keep it paused after the lift and returns a success result naming the action, without calling the media
 * session (no notification access needed). Every other action, and a pause with no hush held, goes to [tool]
 * unchanged. Wraps `portal.media_control` inside [FinalTurnOnSuccess], so a kept pause is a working music command and
 * makes a final turn. Called on the tool-executor thread.
 */
class PauseUnderHush(private val tool: Tool, private val hush: Hush) : Tool {

    override val name: String get() = tool.name

    override val declaration: JSONObject get() = tool.declaration

    override fun invoke(args: JSONObject): JSONObject {
        if (args.optString("action").trim().lowercase() !in PAUSE_ACTIONS || !hush.isHeld) return tool.invoke(args)
        hush.keepPaused()
        return JSONObject().put("action", "pause")
    }

    private companion object {
        // MediaControlTool's words for MediaAction.PAUSE.
        val PAUSE_ACTIONS = setOf("pause", "stop")
    }
}
