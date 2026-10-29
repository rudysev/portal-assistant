package com.portal.assistant.conversation

/**
 * The words in [text] written in capitals for emphasis (`ONLY`, `ARTIST`, `KIND`): the prompt and the built-in
 * declarations use none. A real name written in capitals ("TIDAL", an app) isn't emphasis, so it's allowed.
 */
internal fun shoutedWords(text: String): List<String> =
    SHOUTING.findAll(text).map { it.value }.filterNot { it in NAMES_IN_CAPITALS }.toList()

private val SHOUTING = Regex("""\b[A-Z]{3,}\b""")
private val NAMES_IN_CAPITALS = setOf("TIDAL")
