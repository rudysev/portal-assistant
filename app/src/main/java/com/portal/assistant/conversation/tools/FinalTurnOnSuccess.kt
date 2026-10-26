package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Makes a **music command** that worked a **final turn**: when [tool]'s result has neither `error` nor
 * `searchUnsupported`, [requestFinalTurn] ends the conversation at turn end (after Jarvis finishes speaking) and the
 * result gains an `instruction` telling the model so. A failed or search-unsupported result passes through untouched
 * and the conversation stays open. Called on the tool-executor thread; a cancelled call never runs, so it never ends
 * anything.
 */
class FinalTurnOnSuccess(private val tool: Tool, private val requestFinalTurn: () -> Unit) : Tool {

    override val name: String get() = tool.name

    override val declaration: JSONObject get() = tool.declaration

    override fun invoke(args: JSONObject): JSONObject {
        val result = tool.invoke(args)
        if (result.has("error") || result.has("searchUnsupported")) return result
        requestFinalTurn()
        return result.put("instruction", INSTRUCTION)
    }

    private companion object {
        const val INSTRUCTION = "The conversation ends after your reply: briefly confirm, answer anything else the " +
            "user asked, and don't ask a follow-up question."
    }
}
