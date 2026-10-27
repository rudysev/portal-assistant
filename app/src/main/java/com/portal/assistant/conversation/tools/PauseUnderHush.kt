package com.portal.assistant.conversation.tools

import com.portal.assistant.audio.Hush
import com.portal.assistant.conversation.tools.music.MediaAction
import org.json.JSONObject

/**
 * Makes "pause" / "stop" outlast the **hush**: while [hush] is held, the music is already quiet, so a pause asks the
 * hush to keep it paused after the lift and returns a success result naming the action, without calling the media
 * session (no notification access needed). Every other action, a pause with no hush held, and a pause whose kept
 * pause was denied go to [tool] unchanged. Wraps `portal.media_control` inside [FinalTurnOnSuccess], so a kept pause
 * is a working music command and makes a final turn. Called on the tool-executor thread.
 */
class PauseUnderHush(private val tool: Tool, private val hush: Hush) : Tool {

    override val name: String get() = tool.name

    override val declaration: JSONObject get() = tool.declaration

    override fun invoke(args: JSONObject): ToolOutput {
        val keptPaused = MediaAction.parse(args.optString("action")) == MediaAction.PAUSE && hush.keepPaused()
        return if (keptPaused) ToolOutput(JSONObject().put("action", "pause")) else tool.invoke(args)
    }
}
