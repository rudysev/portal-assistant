package com.portal.assistant.conversation

import com.portal.assistant.conversation.tools.ExternalTools

/**
 * Appends enabled external-tool provider guidance to the system instruction at session setup.
 *
 * Each provider APK supplies a one-sentence [com.portal.assistant.conversation.tools.ToolContract.META_SUMMARY]
 * and tool declarations; [com.portal.assistant.conversation.tools.ExternalToolSpec.parse] turns those into
 * [ExternalTools.promptLines] so the model knows when to call `com.portal.kasa.set_plug` (or calendar,
 * SmartThings, …) instead of a built-in like [com.portal.assistant.conversation.tools.OpenAppTool].
 *
 * Pure (Android-free) so enrichment is unit-tested; prompt bullets come from the same parse pass as
 * declarations (via [com.portal.assistant.conversation.tools.ExternalToolProvider] / [com.portal.assistant.conversation.tools.ToolRegistry]).
 */
object ExternalToolPrompt {

    const val HEADER = "External tools:"

    /** Append an external-tools block when [lines] is non-empty. */
    fun enrich(systemPrompt: String, lines: List<String>): String =
        if (lines.isEmpty()) systemPrompt else "$systemPrompt\n\n$HEADER\n" + lines.joinToString("\n")

    /** Prompt bullets from a session [parse][com.portal.assistant.conversation.tools.ExternalToolSpec.parse] result. */
    fun lines(tools: ExternalTools): List<String> = tools.promptLines
}
