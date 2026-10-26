package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Ends the conversation when the user asks to: [requestFinalTurn] makes this turn a final turn, so the conversation
 * ends at turn end (once the sign-off has been heard) instead of listening again. Called on the tool-executor thread;
 * a cancelled call never runs, so it never ends anything.
 */
class EndConversationTool(private val requestFinalTurn: () -> Unit) : Tool {

    override val name = "portal.end_conversation"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.end_conversation",
           "description":"End the conversation. Call ONLY when the user clearly asks to end or dismiss it ('goodbye', 'bye', 'that's all', 'we're done', 'stop listening', 'never mind'); never for thanks or acknowledgements alone ('thanks', 'ok', 'perfect').",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    override fun invoke(args: JSONObject): JSONObject {
        requestFinalTurn()
        return JSONObject()
            .put("ok", true)
            .put("instruction", "Reply with one brief sign-off and no question; the conversation then ends.")
    }
}
