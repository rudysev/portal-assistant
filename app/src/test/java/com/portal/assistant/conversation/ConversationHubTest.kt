package com.portal.assistant.conversation

import com.portal.assistant.conversation.ConversationHub.UiPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle semantics of the session bridge. `ConversationHub` is a process singleton, so each test sets the
 * state it needs via a writer first and an [After] teardown resets it (no reliance on cross-test order).
 * Pure JVM — StateFlow has no Android deps.
 */
class ConversationHubTest {

    private val session get() = ConversationHub.session.value
    private val notice get() = ConversationHub.notice.value

    // ConversationHub is a process singleton; reset to the greeting after each test so any future hub-reading
    // test can't be order-dependent on the state this one left behind.
    @After fun resetHub() = ConversationHub.clearHistory()

    @Test fun startFreshIsConnectingAndEmpty() {
        ConversationHub.setTurns(listOf(Turn(Role.MODEL, "stale"))) // ensure prior turns are dropped
        ConversationHub.startFresh()
        assertEquals(UiPhase.CONNECTING, session.phase)
        assertEquals(emptyList<Turn>(), session.turns)
    }

    @Test fun startResumeKeepsTurns() {
        ConversationHub.startFresh()
        val turns = listOf(Turn(Role.USER, "q"), Turn(Role.MODEL, "a"))
        ConversationHub.setTurns(turns)
        ConversationHub.startResume()
        assertEquals(UiPhase.CONNECTING, session.phase)
        assertEquals(turns, session.turns) // kept so the continued turn can replay them as context
    }

    @Test fun clearHistoryDropsToEmptyGreeting() {
        ConversationHub.startFresh()
        ConversationHub.setTurns(listOf(Turn(Role.MODEL, "a")))
        ConversationHub.clearHistory()
        assertEquals(UiPhase.IDLE, session.phase)
        assertEquals(emptyList<Turn>(), session.turns)
    }

    @Test fun markIdleKeepsTurnsButLeavesLive() {
        ConversationHub.startFresh()
        val turns = listOf(Turn(Role.MODEL, "answer"))
        ConversationHub.setTurns(turns)
        ConversationHub.setPhase(UiPhase.SPEAKING)
        ConversationHub.markIdle()
        assertEquals(UiPhase.IDLE, session.phase)
        assertEquals(turns, session.turns) // finished transcript stays on screen (until reopen-idle / new / "+")
    }

    @Test fun postNoticeThenClearNotice() {
        ConversationHub.postNotice("offline")
        assertEquals("offline", notice)
        ConversationHub.clearNotice()
        assertNull(notice)
    }

    @Test fun startFreshClearsNotice() {
        ConversationHub.postNotice("couldn't connect")
        ConversationHub.startFresh()
        assertNull(notice) // a new attempt drops the stale failure banner
    }

    @Test fun startResumeClearsNotice() {
        ConversationHub.postNotice("connection lost")
        ConversationHub.startResume()
        assertNull(notice)
    }

    @Test fun clearHistoryClearsNotice() {
        ConversationHub.postNotice("mic denied")
        ConversationHub.clearHistory()
        assertNull(notice)
    }

    @Test fun markIdleKeepsNotice() {
        // A failure ends the turn (markIdle) right after posting the banner — it must survive that, or the
        // user would never see why the conversation stopped.
        ConversationHub.startFresh()
        ConversationHub.postNotice("connection lost")
        ConversationHub.markIdle()
        assertEquals("connection lost", notice)
    }

    @Test fun eachStartGetsAStrictlyIncreasingId() {
        ConversationHub.startFresh()
        val a = session.id
        ConversationHub.startFresh()
        val b = session.id
        ConversationHub.clearHistory()
        val c = session.id
        assertTrue(b > a)
        assertTrue(c > b)
    }
}
