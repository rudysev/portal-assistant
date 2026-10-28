package com.portal.assistant.gemini

/**
 * The Models the Gemini voice backend can run, in one place: each one's id (opens the Live session), its name (the
 * one name shown everywhere — Settings → Model and the top-left label) and the per-model facts its setup frame
 * depends on. Adding a Model = adding one entry; nothing else in the app spells a model id.
 */
object GeminiModel {

    /** One Model. [googleSearch]: it offers Google Search, so setup asks for it. */
    data class Model(val id: String, val name: String, val googleSearch: Boolean)

    /** The Model every install starts on, and the fallback for a saved id no longer offered. */
    val DEFAULT = Model("gemini-2.5-flash-native-audio-latest", "Gemini 2.5", googleSearch = true)

    /** Models the user can pick in Settings, in the order they're listed — [DEFAULT] first. */
    val AVAILABLE: List<Model> = listOf(
        DEFAULT,
        Model("gemini-3.8-live", "Gemini 3.8", googleSearch = false),
    )

    /** The offered Model with [id], or null when it isn't (or is no longer) offered. */
    fun find(id: String): Model? = AVAILABLE.firstOrNull { it.id == id }

    /** The offered Model with [id], else [DEFAULT]. */
    fun of(id: String): Model = find(id) ?: DEFAULT
}
