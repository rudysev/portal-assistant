package com.portal.assistant.conversation

/**
 * Strips the common markdown the model emits (bold, italics, headings, bullet/numbered list markers, inline
 * code, links) so the spoken-answer transcript reads as plain prose instead of raw `**`/`1.` markup — the
 * answer is *spoken*, so the on-screen text should match, not show the model's formatting syntax.
 *
 * Pure and unit-tested (same pattern as [RevealProgress] / [com.portal.assistant.conversation.tools.Timers]).
 * Conservative by design: it only removes unambiguous markup and leaves ordinary punctuation alone (so
 * "1.5 miles" or "e.g." survive untouched). Idempotent — re-stripping an already-clean string is a no-op,
 * which lets [Transcript] re-strip the full streamed accumulation on every delta.
 */
object Markdown {

    private val link = Regex("""\[([^\]]+)]\([^)]*\)""") // [text](url) -> text
    private val heading = Regex("""(?m)^[ \t]*#{1,6}[ \t]+""") // "## " at line start
    private val listMarker = Regex("""(?m)^[ \t]*(?:[-*+]|\d+\.)[ \t]+""") // "- ", "* ", "1. " at line start

    fun strip(s: String): String {
        if (s.isEmpty()) return s
        var t = link.replace(s) { it.groupValues[1] }
        t = heading.replace(t, "")
        t = listMarker.replace(t, "") // before removing '*' below, so '*' bullets are caught here
        // Emphasis + inline code. Paired '__' (bold) is removed; a lone '_' is left (snake_case is common).
        t = t.replace("**", "").replace("__", "").replace("*", "").replace("`", "")
        return t
    }
}
