package com.portal.assistant.wake

import kotlin.random.Random

/** Short, locally selected opening lines spoken before a wake-triggered conversation starts listening. */
object WakeAcknowledgement {
    internal val phrases = listOf(
        "Yes?",
        "Hmm?",
        "I'm listening.",
        "How can I help?",
        "What can I do for you?",
        "At your service.",
    )

    fun prompt(random: Random = Random.Default): String = promptFor(phrases.random(random))

    internal fun promptFor(phrase: String): String =
        "You were just summoned by your wake phrase. Say exactly: \"$phrase\" Say nothing else, " +
            "and then wait for the user's request."
}
