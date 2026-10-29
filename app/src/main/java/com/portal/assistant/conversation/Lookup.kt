package com.portal.assistant.conversation

import com.portal.assistant.system.AppPrefs

/**
 * A **Lookup**: Jarvis fetching live information while it answers. A conversation has only the lookups decided when
 * it opens ([decide]); the declared tools and the prompt's Google Search line ([SystemPrompt]) both follow that
 * decision, so they can't disagree.
 *
 * [toolName] is the function a lookup is declared as, or null for [GOOGLE_SEARCH] (the model's own, asked for in
 * setup rather than declared).
 */
enum class Lookup(val toolName: String?) {
    GOOGLE_SEARCH(null),
    WEATHER("portal.get_weather"),
    WEB_SEARCH("portal.web_search"),
    ;

    companion object {

        /**
         * The lookups a conversation may have: a model with Google Search keeps it and gets nothing else; a model
         * without it gets Weather, and Web search only with a Search key. The Local backend gets none (follow-up
         * ticket: live info on Local).
         */
        fun decide(
            kind: AppPrefs.VoiceBackendKind,
            modelOffersGoogleSearch: Boolean,
            searchKeyPresent: Boolean,
        ): Set<Lookup> = when {
            kind == AppPrefs.VoiceBackendKind.LOCAL -> emptySet()
            modelOffersGoogleSearch -> setOf(GOOGLE_SEARCH)
            searchKeyPresent -> setOf(WEATHER, WEB_SEARCH)
            else -> setOf(WEATHER)
        }

        /** Every lookup tool's name, so a registered one the opening didn't decide on is left undeclared. */
        val toolNames: Set<String> = entries.mapNotNull { it.toolName }.toSet()
    }
}
