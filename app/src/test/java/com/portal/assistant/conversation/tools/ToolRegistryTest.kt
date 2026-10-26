package com.portal.assistant.conversation.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM test of which built-ins [ToolRegistry] makes final-turn tools: exactly the three music commands
 * (`play_music`, `media_control`, `set_repeat`). `now_playing` expects an answer, and non-music tools stay as they are.
 */
class ToolRegistryTest {

    private val builtInNames = listOf(
        "portal.get_time", "portal.set_timer", "portal.list_timers", "portal.cancel_timer",
        "portal.get_volume", "portal.set_volume", "portal.adjust_volume", "portal.set_mute",
        "portal.get_brightness", "portal.set_brightness", "portal.adjust_brightness",
        "portal.get_do_not_disturb", "portal.set_do_not_disturb",
        "portal.media_control", "portal.set_repeat", "portal.now_playing", "portal.play_music",
        "portal.open_app",
    )

    @Test fun onlyMusicCommandsThatWorkEndTheConversation() {
        val ended = mutableListOf<String>()
        var current = ""
        val tools = ToolRegistry.finalTurnOnMusicCommands(builtInNames.map(::OkTool)) { ended += current }

        tools.forEach { tool ->
            current = tool.name
            tool.invoke(JSONObject())
        }

        assertEquals(listOf("portal.media_control", "portal.set_repeat", "portal.play_music"), ended)
    }

    @Test fun wrappingKeepsEveryToolInOrder() {
        val tools = ToolRegistry.finalTurnOnMusicCommands(builtInNames.map(::OkTool)) {}

        assertEquals(builtInNames, tools.map { it.name })
    }

    private class OkTool(override val name: String) : Tool {
        override val declaration: JSONObject = JSONObject().put("name", name)

        override fun invoke(args: JSONObject): JSONObject = JSONObject().put("ok", true)
    }
}
