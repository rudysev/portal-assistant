package com.portal.assistant.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the resume context helpers (no socket, no Android). */
class ResumeContextTest {

    private fun turn(role: Role, text: String) = Turn(role, text)

    // The exact header withHistory inserts before the transcript (kept in sync with ResumeContext).
    private val header = "Here is the conversation so far — continue it naturally, using this as context:"

    // ---- recentContext (char budget) ---------------------------------------------------------------

    @Test fun recentContextKeepsAllWhenUnderBudget() {
        val turns = listOf(turn(Role.USER, "abc"), turn(Role.MODEL, "de")) // 5 chars total
        assertEquals(turns, ResumeContext.recentContext(turns, 100))
        assertEquals(turns, ResumeContext.recentContext(turns, 5)) // exactly fits
    }

    @Test fun recentContextDropsOldestOverBudgetKeepingRecentTail() {
        val turns = (1..5).map { turn(Role.USER, "ab") } // 2 chars each
        // Budget 5 → newest two fit (4 ≤ 5); a third (6) would exceed.
        assertEquals(2, ResumeContext.recentContext(turns, 5).size)
        assertEquals(turns.takeLast(2), ResumeContext.recentContext(turns, 5))
    }

    @Test fun recentContextAlwaysKeepsAtLeastTheLatestTurn() {
        val turns = listOf(turn(Role.USER, "old"), turn(Role.MODEL, "a very long final answer"))
        // Even with a tiny/zero budget the latest turn is kept (resume never replays nothing).
        assertEquals(listOf(turns.last()), ResumeContext.recentContext(turns, 1))
        assertEquals(listOf(turns.last()), ResumeContext.recentContext(turns, 0))
    }

    @Test fun recentContextPreservesChatOrderOfMixedRolesAfterTruncation() {
        val turns = listOf(
            turn(Role.USER, "q1"),
            turn(Role.MODEL, "a1"),
            turn(Role.USER, "q2"),
            turn(Role.MODEL, "a2"),
        ) // 8 chars total; budget 6 keeps the newest 3 (a1,q2,a2 = 6) in chat order.
        val recent = ResumeContext.recentContext(turns, 6)
        assertEquals(
            listOf(Role.MODEL to "a1", Role.USER to "q2", Role.MODEL to "a2"),
            recent.map { it.role to it.text },
        )
    }

    @Test fun recentContextThenWithHistoryBuildsTruncatedBlock() {
        val turns = (1..4).map { turn(Role.MODEL, "ans$it") } // 4 chars each
        val result = ResumeContext.withHistory("P", ResumeContext.recentContext(turns, 9)) // newest 2 fit
        assertEquals("P\n\n$header\nAssistant: ans3\nAssistant: ans4", result)
    }

    @Test fun recentContextEmptyStaysEmpty() {
        assertEquals(emptyList<Turn>(), ResumeContext.recentContext(emptyList(), 4000))
    }

    // ---- withHistory -------------------------------------------------------------------------------

    @Test fun withHistoryEmptyReturnsPromptUnchanged() {
        assertEquals("base prompt", ResumeContext.withHistory("base prompt", emptyList()))
    }

    @Test fun withHistoryBuildsExactBlock() {
        val result = ResumeContext.withHistory(
            "base prompt",
            listOf(turn(Role.USER, "what's the weather"), turn(Role.MODEL, "Sunny, 20°C")),
        )
        assertEquals(
            "base prompt\n\n$header\nUser: what's the weather\nAssistant: Sunny, 20°C",
            result,
        )
    }

    @Test fun withHistoryPreservesNewlinesAndQuotesInTurnText() {
        val result = ResumeContext.withHistory(
            "P",
            listOf(turn(Role.USER, "line1\nline2"), turn(Role.MODEL, "she said \"hi\"")),
        )
        // Turn text is embedded verbatim (no escaping/normalisation — it's plain prompt text, not JSON).
        assertTrue(result.endsWith("User: line1\nline2\nAssistant: she said \"hi\""))
    }
}
