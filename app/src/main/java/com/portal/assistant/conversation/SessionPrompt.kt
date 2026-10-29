package com.portal.assistant.conversation

/**
 * Pure assembly of the session system instruction sent to the voice backend at connect time.
 *
 * Layers (in order, Markdown `#` sections):
 * 1. [SystemPrompt] — Role and Objective, Personality and Tone, Speaking, Tools (Google Search line when a Lookup)
 * 2. [ExternalToolPrompt] — enabled external-provider bullets, under `# Tools` (when any)
 * 3. [SystemContext] — `# Device Context`: time at conversation start and location
 * 4. [ResumeContext] — `# Conversation So Far`: prior transcript on a mic-tap resume (when any), always last
 *
 * Android-free so the layering is unit-tested; [ConversationOpening] calls it with [externalToolLines] from
 * [com.portal.assistant.conversation.tools.ToolRegistry.externalPromptLines] (same session parse as tool
 * declarations) and [deviceContextLines] built from the opening's clock/location snapshot.
 */
object SessionPrompt {

    fun build(
        builtinRules: String,
        externalToolLines: List<String>,
        deviceContextLines: List<String>,
        resumeTurns: List<Turn> = emptyList(),
    ): String {
        val withExternalTools = ExternalToolPrompt.enrich(builtinRules, externalToolLines)
        val withDeviceContext = SystemContext.enrich(withExternalTools, deviceContextLines)
        return ResumeContext.withHistory(withDeviceContext, resumeTurns)
    }
}
