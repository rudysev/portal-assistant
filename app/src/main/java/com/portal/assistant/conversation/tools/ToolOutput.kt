package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * What one tool call returns: the [response] for the model and, when the call makes its turn a **final turn**, the
 * [finalTurnInstruction] telling the model the conversation ends after its reply. The two can't be separated: the
 * [ToolRunner] adds the instruction to the response and marks the result, so the model is told exactly when the
 * conversation will end. The mark counts only if the call settles with this output, not as cancelled.
 */
data class ToolOutput(val response: JSONObject, val finalTurnInstruction: String? = null)
