package com.portal.assistant.conversation

/**
 * Pure assembly of the session system instruction sent to the voice backend at connect time: the built-in sections
 * ([SystemPrompt]), then [SystemContext]'s device snapshot, then on a mic-tap resume [ResumeContext]'s prior
 * transcript.
 *
 * Android-free so the layering is unit-tested; [ConversationOpening] calls it with [deviceContextLines] built from
 * the opening's clock/location snapshot.
 */
object SessionPrompt {

    fun build(lookups: Set<Lookup>, deviceContextLines: List<String>, resumeTurns: List<Turn> = emptyList()): String {
        val builtin = SystemPrompt.build(lookups)
        val withDeviceContext = SystemContext.enrich(builtin, deviceContextLines)
        return ResumeContext.withHistory(withDeviceContext, resumeTurns)
    }
}
