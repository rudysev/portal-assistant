package com.portal.assistant.conversation

/**
 * Pure helpers for **resuming** a conversation. When the user starts a turn while a finished transcript is
 * still on screen, we open a fresh Live socket and give the model the recent turns as context so it
 * remembers what was just said. No Android, no I/O — so it's unit-tested (the same pure-logic pattern as
 * [LiveClient.buildSetup][com.portal.assistant.gemini.LiveClient] and [Transcript]).
 *
 * The history is injected into the **system instruction** rather than a Live `clientContent` message: the
 * system instruction is always part of the prompt, whereas a `clientContent` seed (`turnComplete:false`)
 * did not reliably establish context on device.
 */
object ResumeContext {

    /**
     * The most recent [turns] whose combined text fits within [maxChars] — bounds the resumed prompt by
     * *size* (a pragmatic token proxy) rather than a fixed turn count. Walks newest→oldest keeping turns
     * while they fit, but **always keeps at least the latest turn** (so resume never replays nothing, even if
     * that one turn alone exceeds the budget). Returns a fresh list in chat order (oldest→newest). The
     * labels/newlines [withHistory] adds aren't counted — the bound is approximate, which is fine.
     */
    fun recentContext(turns: List<Turn>, maxChars: Int): List<Turn> {
        val kept = ArrayDeque<Turn>()
        var used = 0
        for (turn in turns.asReversed()) {
            if (kept.isNotEmpty() && used + turn.text.length > maxChars) break
            kept.addFirst(turn)
            used += turn.text.length
        }
        return kept.toList()
    }

    /**
     * Append the prior conversation [turns] to [systemPrompt] as a labelled transcript so the resumed
     * session continues with context. Returns [systemPrompt] unchanged when there is nothing to replay.
     * [Role.USER] → `"User"`, [Role.MODEL] → `"Assistant"`.
     */
    fun withHistory(systemPrompt: String, turns: List<Turn>): String {
        if (turns.isEmpty()) return systemPrompt
        val history = turns.joinToString("\n") { turn ->
            val who = if (turn.role == Role.USER) "User" else "Assistant"
            "$who: ${turn.text}"
        }
        return "$systemPrompt\n\n$HISTORY_HEADER\n$history"
    }

    private const val HISTORY_HEADER =
        "Here is the conversation so far — continue it naturally, using this as context:"
}
