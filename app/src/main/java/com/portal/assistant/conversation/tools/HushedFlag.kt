package com.portal.assistant.conversation.tools

import com.portal.assistant.audio.Hush
import org.json.JSONObject

/**
 * Keeps the **hush** invisible to `portal.now_playing`: while [hush] is held, not turned into a kept pause, and
 * silenced audio that was playing, the music app reports paused only because Jarvis is listening, so a track [tool]
 * found (its result names an `app`) is reported `"playing": true` with `"hushed": true`. A `hushed` flag beside
 * `"playing": false` wasn't enough: on a gen2 the model still called the track paused. A track already paused when
 * the hush started, no session found, an error, or no hush: the result passes through untouched. Called on the
 * tool-executor thread.
 */
class HushedFlag(private val tool: Tool, private val hush: Hush) : Tool {

    override val name: String get() = tool.name

    override val declaration: JSONObject get() = tool.declaration

    override fun invoke(args: JSONObject): ToolOutput {
        val output = tool.invoke(args)
        val hushed = hush.isHeld && !hush.isKeptPaused && hush.silencedPlayback && output.response.has("app")
        return if (hushed) output.copy(response = output.response.put("playing", true).put("hushed", true)) else output
    }
}
