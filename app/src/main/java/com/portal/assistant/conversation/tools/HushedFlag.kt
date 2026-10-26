package com.portal.assistant.conversation.tools

import com.portal.assistant.audio.Hush
import org.json.JSONObject

/**
 * Keeps the **hush** invisible to `portal.now_playing`: while [hush] is held and not turned into a kept pause, the
 * music app reports paused only because Jarvis is listening, so [tool]'s result gains `"hushed": true` (the tool's
 * description tells the model to describe the track as playing). Otherwise the result passes through untouched.
 * Called on the tool-executor thread.
 */
class HushedFlag(private val tool: Tool, private val hush: Hush) : Tool {

    override val name: String get() = tool.name

    override val declaration: JSONObject get() = tool.declaration

    override fun invoke(args: JSONObject): JSONObject {
        val result = tool.invoke(args)
        return if (hush.isHeld && !hush.isKeptPaused) result.put("hushed", true) else result
    }
}
