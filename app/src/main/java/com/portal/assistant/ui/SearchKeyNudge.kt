package com.portal.assistant.ui

import android.content.Context
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.AppPrefs.VoiceBackendKind

/** The idle home's "Add a search key" suggestion, as its line begins. */
internal const val SEARCH_KEY_NUDGE = "Add a search key"

/**
 * Whether the idle home suggests adding a Search key: on Gemini with a model without Google Search
 * ([GeminiModel.Model.googleSearch]) and no Search key stored — the one case where a key would add Web search.
 * A model with Google Search already looks things up, and the Local backend gets no Jarvis lookups. Pure.
 */
internal fun showsSearchKeyNudge(
    backend: VoiceBackendKind,
    model: GeminiModel.Model,
    searchKeyPresent: Boolean,
): Boolean = backend == VoiceBackendKind.GEMINI && !model.googleSearch && !searchKeyPresent

/** [showsSearchKeyNudge] for [backend], with the selected model and the stored Search key read from prefs. */
internal fun showsSearchKeyNudge(context: Context, backend: VoiceBackendKind): Boolean = showsSearchKeyNudge(
    backend = backend,
    model = GeminiModel.of(AppPrefs.modelId(context)),
    searchKeyPresent = AppPrefs.hasSearchKey(context),
)
