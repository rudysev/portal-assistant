package com.portal.assistant.ui

import android.content.Context
import com.portal.assistant.conversation.Lookup
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.AppPrefs.VoiceBackendKind

/** The idle home's "Add a search key" suggestion, as its line begins. */
internal const val SEARCH_KEY_NUDGE = "Add a search key"

/**
 * Whether the idle home suggests adding a Search key: when the opening's own lookup decision ([Lookup.decide]) would
 * declare Web search with a key but doesn't without one, i.e. a key would add it. So it follows that decision: shown
 * on Gemini with a model without Google Search ([GeminiModel.Model.googleSearch]) and no key stored; not on a model
 * with Google Search (it already looks things up) or the Local backend (no Jarvis lookups). Pure.
 */
internal fun showsSearchKeyNudge(
    backend: VoiceBackendKind,
    model: GeminiModel.Model,
    searchKeyPresent: Boolean,
): Boolean {
    fun webSearchWith(key: Boolean) = Lookup.WEB_SEARCH in Lookup.decide(backend, model.googleSearch, key)
    return webSearchWith(key = true) && !webSearchWith(key = searchKeyPresent)
}

/** [showsSearchKeyNudge] for [backend], with the selected model and the stored Search key read from prefs. */
internal fun showsSearchKeyNudge(context: Context, backend: VoiceBackendKind): Boolean = showsSearchKeyNudge(
    backend = backend,
    model = GeminiModel.of(AppPrefs.modelId(context)),
    searchKeyPresent = AppPrefs.hasSearchKey(context),
)
