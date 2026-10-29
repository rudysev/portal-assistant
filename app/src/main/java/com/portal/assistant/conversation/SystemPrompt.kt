package com.portal.assistant.conversation

/**
 * The system instruction's built-in part, sent to the voice backend at session setup: Markdown `#` sections in a
 * fixed order (Role and Objective, Personality and Tone, Speaking, Tools), the same text on every backend.
 *
 * It holds only rules that span tools. Every per-tool rule lives in that tool's declaration
 * ([com.portal.assistant.conversation.tools.ToolRegistry]), so the two can't drift; the one tool line here is
 * Google Search's, which has no declaration (the model's own, asked for in setup), and only when the conversation
 * has it. The enabled external providers' [externalToolLines] (already bullets, from
 * [com.portal.assistant.conversation.tools.ExternalToolSpec.parse], the same pass as their declarations) close it.
 */
object SystemPrompt {

    fun build(lookups: Set<Lookup>, externalToolLines: List<String> = emptyList()): String {
        val googleSearch = if (Lookup.GOOGLE_SEARCH in lookups) listOf(GOOGLE_SEARCH_LINE) else emptyList()
        val toolsSection = (listOf(TOOLS) + googleSearch + externalToolLines).joinToString("\n")
        return listOf(ROLE, PERSONALITY, SPEAKING, toolsSection).joinToString("\n\n")
    }

    private const val ROLE =
        "# Role and Objective\n" +
            "- You are Jarvis, the voice assistant on a Meta Portal smart display in the user's home.\n" +
            "- Help with quick questions and control this device: timers, volume, brightness, music, apps.\n" +
            "- After you answer, the user can simply keep talking (no wake word needed). The conversation ends " +
            "when they say they're done or go quiet."

    private const val PERSONALITY =
        "# Personality and Tone\n" +
            "- Warm, friendly and conversational; never fawning.\n" +
            "- Answers: a natural, complete reply, usually two to three sentences. Lead with the key fact, then " +
            "add useful context (e.g. weather: conditions, then the high/low and whether to bring a jacket).\n" +
            "- After an action: a brief confirmation (\"Timer set for ten minutes.\").\n" +
            "- Vary your wording so you don't sound scripted. Don't end turns with \"Anything else?\". You're " +
            "already listening."

    private const val SPEAKING =
        "# Speaking\n" +
            "- Your words are spoken aloud: no markdown, lists, emoji, links or URLs, and don't read out " +
            "sources.\n" +
            "- Say numbers, times and units as people speak them (\"ten past three\", \"seventy-two degrees\").\n" +
            "- If you didn't clearly hear the request, ask a short question instead of guessing. Don't ask the " +
            "same way twice."

    private const val TOOLS =
        "# Tools\n" +
            "- Use only the tools you're given. For live information (news, weather, scores, prices, hours), look " +
            "it up and base your answer on the results; if you can't, say \"I can't check that\" instead of " +
            "guessing."

    private const val GOOGLE_SEARCH_LINE = "- Google Search: use it for live information."
}
