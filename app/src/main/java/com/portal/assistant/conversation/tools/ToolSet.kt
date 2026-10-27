package com.portal.assistant.conversation.tools

import org.json.JSONObject

/**
 * The tools one conversation can call: what to declare to the model, the external providers' prompt lines, and how
 * to invoke one by name. [ToolRegistry] is the production set; tests drive the [ToolRunner] with a fake.
 */
interface ToolSet {
    /** The function declarations sent in the session setup. */
    fun declarations(): List<JSONObject>

    /** Enabled external-provider bullets for the session system prompt — same snapshot as [declarations]. */
    fun externalPromptLines(): List<String>

    /** Run tool [name] with [args]. Called on the tool-executor thread; may block; must not touch UI. */
    fun invoke(name: String, args: JSONObject): ToolOutput

    /** Release any tool-held resources on conversation teardown. */
    fun dispose()
}
