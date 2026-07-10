package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.BackendConfig
import com.portal.assistant.conversation.backend.Backends
import org.json.JSONObject

/** Pure assembly of [BackendConfig] from a resolved [Backends.Choice] plus per-session fields. */
object BackendSessionConfig {

    fun build(
        choice: Backends.Choice,
        model: String,
        systemPrompt: String,
        functionDeclarations: List<JSONObject>,
    ): BackendConfig = choice.defaultWire.applyTo(
        BackendConfig(
            credential = choice.credential,
            model = model,
            systemPrompt = systemPrompt,
            functionDeclarations = functionDeclarations,
            kind = choice.kind,
        ),
    )
}
