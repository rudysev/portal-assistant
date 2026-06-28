package com.portal.assistant.conversation

import java.util.concurrent.atomic.AtomicLong

/** Who said it. */
enum class Role { USER, MODEL }

/**
 * One turn in the chat transcript. [id] is a stable, monotonic identity so the UI can key list items
 * and patch a turn in place as its [text] streams in (Compose `LazyColumn` keying + the word-by-word
 * reveal). `copy(text = …)` preserves the id, so a growing turn stays the same item.
 */
data class Turn(
    val role: Role,
    val text: String,
    val id: Long = nextId.incrementAndGet(),
    /**
     * Ephemeral **render hint** (not content): how many leading words to show while this model turn streams,
     * stamped by the engine on the latest model turn so the UI reveals words in step with the voice.
     * `null` = show the full [text] (every non-active/finished turn). See [RevealProgress].
     */
    val revealedWords: Int? = null,
) {
    private companion object {
        val nextId = AtomicLong(0)
    }
}
