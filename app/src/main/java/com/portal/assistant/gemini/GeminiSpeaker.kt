package com.portal.assistant.gemini

/**
 * The Speakers the Gemini voice backend can talk with, in one place — the prebuilt voices Google documents at
 * docs.cloud.google.com/text-to-speech/docs/gemini-tts, labelled Male/Female as Google labels them. No Gemini
 * API lists voices, so this catalog is the source of truth (like [GeminiModel.AVAILABLE]): nothing else in the
 * app spells a Speaker name, and adding a Speaker = adding one entry. A null Speaker means **Server default**
 * (Gemini picks its own voice — don't assume which).
 */
object GeminiSpeaker {

    enum class Gender(val label: String) { MALE("Male"), FEMALE("Female") }

    data class Speaker(val name: String, val gender: Gender) {
        /** How Settings shows it, e.g. "Charon · Male". */
        val label: String get() = "$name · ${gender.label}"
    }

    /** Every Speaker, alphabetical by name (the order Settings lists them in). */
    val AVAILABLE: List<Speaker> = listOf(
        Speaker("Achernar", Gender.FEMALE),
        Speaker("Achird", Gender.MALE),
        Speaker("Algenib", Gender.MALE),
        Speaker("Algieba", Gender.MALE),
        Speaker("Alnilam", Gender.MALE),
        Speaker("Aoede", Gender.FEMALE),
        Speaker("Autonoe", Gender.FEMALE),
        Speaker("Callirrhoe", Gender.FEMALE),
        Speaker("Charon", Gender.MALE),
        Speaker("Despina", Gender.FEMALE),
        Speaker("Enceladus", Gender.MALE),
        Speaker("Erinome", Gender.FEMALE),
        Speaker("Fenrir", Gender.MALE),
        Speaker("Gacrux", Gender.FEMALE),
        Speaker("Iapetus", Gender.MALE),
        Speaker("Kore", Gender.FEMALE),
        Speaker("Laomedeia", Gender.FEMALE),
        Speaker("Leda", Gender.FEMALE),
        Speaker("Orus", Gender.MALE),
        Speaker("Puck", Gender.MALE),
        Speaker("Pulcherrima", Gender.FEMALE),
        Speaker("Rasalgethi", Gender.MALE),
        Speaker("Sadachbia", Gender.MALE),
        Speaker("Sadaltager", Gender.MALE),
        Speaker("Schedar", Gender.MALE),
        Speaker("Sulafat", Gender.FEMALE),
        Speaker("Umbriel", Gender.MALE),
        Speaker("Vindemiatrix", Gender.FEMALE),
        Speaker("Zephyr", Gender.FEMALE),
        Speaker("Zubenelgenubi", Gender.MALE),
    )

    /** The catalog Speaker with exactly this [name], or null (Server default) for null or a name the catalog
     *  doesn't hold — the read contract prefs self-heal against. */
    fun find(name: String?): Speaker? = AVAILABLE.firstOrNull { it.name == name }
}
