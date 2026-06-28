package com.portal.assistant.gemini

/**
 * The Gemini model the assistant runs, in one place: the [ID] used to open the Live session and the
 * [prettyName] shown as the chat subtitle. Co-locating them means swapping the model updates the UI label
 * automatically — no hard-coded "Gemini" string to drift. [prettyName] is pure, so it's unit-tested.
 */
object GeminiModel {

    const val ID = "gemini-2.5-flash-native-audio-latest"

    /** Models the user can pick in Settings — [ID] is the default. Append here to expose a new option. */
    val AVAILABLE: List<String> = listOf(ID)

    /**
     * Vendor + version from a model id, e.g. "gemini-2.5-flash-native-audio-latest" → "Gemini 2.5".
     * Falls back to just the capitalized vendor when there's no numeric version token.
     */
    fun prettyName(id: String = ID): String {
        val tokens = id.removePrefix("models/").split("-").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return id
        val vendor = tokens[0].replaceFirstChar { it.uppercase() }
        val version = tokens.getOrNull(1)?.takeIf { it.first().isDigit() }
        return listOfNotNull(vendor, version).joinToString(" ")
    }
}
