package com.portal.assistant.conversation

/**
 * Pure assembly of the session system instruction sent to the voice backend at connect time.
 *
 * Layers (in order):
 * 1. [SystemPrompt] — role + built-in tool usage rules
 * 2. [ExternalToolPrompt] — enabled external-provider guidance (when any)
 * 3. [SystemContext] — device clock/location snapshot
 * 4. [ResumeContext] — prior transcript on tap-to-talk resume (when any)
 *
 * Android-free so the layering is unit-tested; [com.portal.assistant.conversation.AssistantEngine]
 * supplies [externalToolLines] from [com.portal.assistant.conversation.tools.ToolRegistry.externalPromptLines]
 * (same session parse as tool declarations) and [deviceContextLines] from the device clock/location.
 */
object SessionPrompt {

    fun build(
        builtinRules: String = SystemPrompt.build(),
        externalToolLines: List<String>,
        deviceContextLines: List<String>,
        resumeTurns: List<Turn> = emptyList(),
    ): String {
        val withExternalTools = ExternalToolPrompt.enrich(builtinRules, externalToolLines)
        val withDeviceContext = SystemContext.enrich(withExternalTools, deviceContextLines)
        return ResumeContext.withHistory(withDeviceContext, resumeTurns)
    }
}
