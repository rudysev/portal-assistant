package com.portal.assistant.conversation.tools.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The fake keeps the real adapter's coupling: an index means the list from the last [MediaSessions.active] read. */
class FakeMediaSessionsTest {

    private val session = ActiveSession(
        SessionInfo(
            index = 0,
            pkg = "com.spotify.music",
            isPlaying = true,
            canPlay = true,
            canPause = true,
            canNext = true,
            canPrev = true,
            hasMetadata = true,
            canSetRepeat = true,
        ),
    )

    @Test fun transportBeforeAnyReadFails() {
        val sessions = FakeMediaSessions(listOf(session))

        assertThrows(IndexOutOfBoundsException::class.java) { sessions.transport(0, MediaAction.PLAY) }
        assertEquals(emptyList<String>(), sessions.calls)
    }

    @Test fun setRepeatBeforeAnyReadFails() {
        val sessions = FakeMediaSessions(listOf(session))

        assertThrows(IndexOutOfBoundsException::class.java) { sessions.setRepeat(0, RepeatMode.ONE) }
        assertEquals(emptyList<String>(), sessions.calls)
    }

    @Test fun anIndexPastTheLastReadFails() {
        val sessions = FakeMediaSessions(emptyList())
        sessions.active()
        sessions.sessions = listOf(session) // changed after the read: the index still means the old list

        assertThrows(IndexOutOfBoundsException::class.java) { sessions.transport(0, MediaAction.PLAY) }
    }

    @Test fun anIndexFromTheLastReadDrivesThatSession() {
        val sessions = FakeMediaSessions(listOf(session))
        sessions.active()

        sessions.transport(0, MediaAction.PAUSE)
        sessions.setRepeat(0, RepeatMode.ALL)

        assertEquals(listOf("pause 0", "repeat all 0"), sessions.calls)
    }
}
