package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * Ends the conversation when the user asks to: its output carries a final-turn instruction, so this turn is a final
 * turn and the conversation ends at turn end (once the sign-off has been heard) instead of listening again. Called on
 * the tool-executor thread. A call cancelled before or while it runs ends nothing ([ToolRunner]).
 */
object EndConversationTool : Tool {

    override val name = "portal.end_conversation"

    override val declaration: JSONObject = JSONObject(
        """{"name":"portal.end_conversation",
           "description":"End the conversation. Call it only when the user clearly asks to end or dismiss it ('goodbye', 'bye', 'that's all', 'we're done', 'stop listening', 'never mind'), then give one brief sign-off; never for thanks or acknowledgements alone ('thanks', 'ok', 'perfect').",
           "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
    )

    // Conditional: Gemini 2.5 often says goodbye in the turn that makes this call, and an unconditional "reply with
    // a sign-off" got it a second one ("Goodbye! Goodbye.").
    override fun invoke(args: JSONObject): ToolOutput = ToolOutput(
        JSONObject().put("ok", true),
        finalTurnInstruction = "If you already said goodbye, say nothing more. Otherwise reply with one brief " +
            "sign-off and no question. The conversation then ends.",
    )
}
