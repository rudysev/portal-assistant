package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Makes a **music command** that worked a **final turn**: when [tool]'s response has neither `error` nor
 * `searchUnsupported`, the output gains a final-turn instruction, so the conversation ends at turn end (after Jarvis
 * finishes speaking) and the model is told so. A failed or search-unsupported output passes through untouched and the
 * conversation stays open. Called on the tool-executor thread. A call cancelled before or while it runs ends nothing
 * ([ToolRunner]).
 */
class FinalTurnOnSuccess(private val tool: Tool) : Tool {

    override val name: String get() = tool.name

    override val declaration: JSONObject get() = tool.declaration

    override fun invoke(args: JSONObject): ToolOutput {
        val output = tool.invoke(args)
        if (output.response.has("error") || output.response.has("searchUnsupported")) return output
        return output.copy(finalTurnInstruction = INSTRUCTION)
    }

    private companion object {
        const val INSTRUCTION = "The conversation ends after your reply: briefly confirm, answer anything else the " +
            "user asked, and don't ask a follow-up question."
    }
}
