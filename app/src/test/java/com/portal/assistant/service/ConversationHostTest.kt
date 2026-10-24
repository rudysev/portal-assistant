package com.portal.assistant.service

import com.portal.assistant.conversation.Conversation
import com.portal.assistant.conversation.Refusal
import com.portal.assistant.conversation.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ConversationHost]: the service's own rules — one conversation at a time, a refusal posts its notice
 * and re-arms wake, every way back to standby re-arms wake — driven through its calls, with recording fakes that all
 * append to one shared [log] so a test asserts the order across them.
 */
class ConversationHostTest {

    private val log = mutableListOf<String>()
    private val conversations = mutableListOf<FakeConversation>()

    /** What the next conversation's `start` answers. */
    private var nextRefusal: Refusal? = null

    private val host = ConversationHost(
        conversations = { onEnded ->
            log += "build"
            FakeConversation(log, nextRefusal, onEnded).also { conversations += it }
        },
        wake = object : ConversationWake {
            override fun conversationStarting() {
                log += "wake starting"
            }

            override fun conversationEnded() {
                log += "wake ended"
            }
        },
        notices = object : ConversationNotices {
            override fun postNotice(notice: String) {
                log += "notice($notice)"
            }

            override fun clearHistory() {
                log += "clear history"
            }
        },
        onActiveChanged = { active -> log += "active=$active" },
    )

    /** The one conversation built so far. */
    private val conversation get() = conversations.single()

    @Test fun aTriggerFreesTheMicSlotThenStartsAConversation() {
        host.start(Trigger.MicTap)

        assertEquals(listOf("wake starting", "build", "start(${Trigger.MicTap.logLabel})", "active=true"), log)
        assertTrue(host.live)
    }

    @Test fun aSecondTriggerDuringALiveConversationStartsNoSecondOne() {
        host.start(Trigger.MicTap)
        log.clear()

        host.start(Trigger.WakeWord("w-1"))

        assertEquals(emptyList<String>(), log)
        assertEquals(1, conversations.size)
    }

    @Test fun aRefusedTriggerPostsItsNoticeAndReArmsWakeOnce() {
        nextRefusal = Refusal("Add a Gemini API key in Settings")

        host.start(Trigger.MicTap)

        assertEquals(
            listOf(
                "wake starting",
                "build",
                "start(${Trigger.MicTap.logLabel})",
                "notice(Add a Gemini API key in Settings)",
                "wake ended",
            ),
            log,
        )
        assertFalse(host.live)
    }

    @Test fun afterARefusalTheNextTriggerStartsAFreshConversation() {
        nextRefusal = Refusal("no key")
        host.start(Trigger.MicTap)
        nextRefusal = null

        host.start(Trigger.MicTap)

        assertEquals(2, conversations.size)
        assertTrue(host.live)
    }

    @Test fun aConversationEndingOnItsOwnReturnsToStandby() {
        host.start(Trigger.MicTap)
        log.clear()

        conversation.endsOnItsOwn()

        assertEquals(listOf("active=false", "wake ended"), log)
        assertFalse(host.live)
    }

    @Test fun afterAConversationEndsTheNextTriggerStartsANewOne() {
        host.start(Trigger.MicTap)
        conversation.endsOnItsOwn()

        host.start(Trigger.WakeWord("w-2"))

        assertEquals(2, conversations.size)
        assertTrue(host.live)
    }

    @Test fun endingStopsTheConversationThenReturnsToStandby() {
        host.start(Trigger.MicTap)
        log.clear()

        host.end()

        assertEquals(listOf("stop", "active=false", "wake ended"), log)
        assertFalse(host.live)
    }

    @Test fun endingWhileIdleStillRefreshesTheNotificationAndReArmsWake() {
        host.end()

        assertEquals(listOf("active=false", "wake ended"), log)
    }

    @Test fun aNewConversationStopsTheLiveOneBeforeClearingTheTranscript() {
        host.start(Trigger.MicTap)
        log.clear()

        host.newConversation()

        assertEquals(listOf("stop", "active=false", "wake ended", "clear history"), log)
        assertFalse(host.live)
    }

    @Test fun closingStopsTheConversationWithoutReturningToStandby() {
        host.start(Trigger.MicTap)
        log.clear()

        host.close()

        assertEquals(listOf("stop"), log)
        assertFalse(host.live)
    }
}

/** A [Conversation] that answers `start` with [refusal] and keeps [onEnded] so the test can end it. */
private class FakeConversation(
    private val log: MutableList<String>,
    private val refusal: Refusal?,
    private val onEnded: () -> Unit,
) : Conversation {
    override fun start(trigger: Trigger): Refusal? {
        log += "start(${trigger.logLabel})"
        return refusal
    }

    override fun stop() {
        log += "stop"
    }

    /** The conversation ended by itself (a goodbye, an error): it has torn down and calls back. */
    fun endsOnItsOwn() = onEnded()
}
