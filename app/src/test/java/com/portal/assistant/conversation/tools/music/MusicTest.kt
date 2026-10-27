package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.FakeHush
import com.portal.assistant.conversation.tools.ToolOutput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for **Music** at the `Tool` seam: each test sets the device's media sessions and the conversation's
 * hush, calls a tool, and pins the JSON the model reads.
 */
class MusicTest {

    private val own = "com.portal.assistant"
    private val sessions = FakeMediaSessions(labels = mapOf("com.spotify.music" to "Spotify"))
    private val hush = FakeHush()

    private fun music() = Music(sessions, hush, own)

    private fun nowPlaying(): ToolOutput = music().nowPlaying.invoke(JSONObject())

    private fun spotify(playing: Boolean, title: String? = "One More Time", artist: String? = "Daft Punk") =
        ActiveSession(
            SessionInfo(
                index = 0,
                pkg = "com.spotify.music",
                isPlaying = playing,
                canPlay = true,
                canPause = true,
                canNext = true,
                canPrev = true,
                hasMetadata = true,
            ),
            title = title,
            artist = artist,
        )

    private fun ToolOutput.keys() = response.keys().asSequence().toSet()

    @Test fun nowPlayingIsDeclaredUnderItsName() {
        val tool = music().nowPlaying

        assertEquals("portal.now_playing", tool.name)
        assertEquals("portal.now_playing", tool.declaration.getString("name"))
    }

    @Test fun nothingPlayingReportsNotPlaying() {
        val result = nowPlaying()

        assertEquals(setOf("playing"), result.keys())
        assertEquals(false, result.response.getBoolean("playing"))
        assertNull(result.finalTurnInstruction)
    }

    @Test fun noSessionAccessAsksForNotificationAccess() {
        sessions.sessions = null

        val result = nowPlaying()

        assertEquals(setOf("error"), result.keys())
        assertEquals(
            "media control isn't enabled — grant notification access in Settings",
            result.response.getString("error"),
        )
        assertNull(result.finalTurnInstruction)
    }

    @Test fun aPlayingTrackReportsItsTitleArtistAndApp() {
        sessions.sessions = listOf(spotify(playing = true))

        val result = nowPlaying()

        assertEquals(setOf("playing", "app", "title", "artist"), result.keys())
        assertEquals(true, result.response.getBoolean("playing"))
        assertEquals("Spotify", result.response.getString("app"))
        assertEquals("One More Time", result.response.getString("title"))
        assertEquals("Daft Punk", result.response.getString("artist"))
        assertNull(result.finalTurnInstruction)
    }

    @Test fun aPlayingTrackWithNoTitleOrArtistLeavesThemOut() {
        sessions.sessions = listOf(spotify(playing = true, title = null, artist = null))

        val result = nowPlaying()

        assertEquals(setOf("playing", "app"), result.keys())
        assertEquals(true, result.response.getBoolean("playing"))
        assertNull(result.finalTurnInstruction)
    }

    @Test fun aPausedTrackReportsNotPlaying() {
        sessions.sessions = listOf(spotify(playing = false))

        val result = nowPlaying()

        assertEquals(setOf("playing", "app", "title", "artist"), result.keys())
        assertEquals(false, result.response.getBoolean("playing"))
        assertNull(result.finalTurnInstruction)
    }

    @Test fun aTrackPausedOnlyByTheHushReportsPlayingAndHushed() {
        hush.start()
        sessions.sessions = listOf(spotify(playing = false))

        val result = nowPlaying()

        assertEquals(setOf("playing", "app", "title", "artist", "hushed"), result.keys())
        assertEquals(true, result.response.getBoolean("playing"))
        assertEquals(true, result.response.getBoolean("hushed"))
        assertEquals("One More Time", result.response.getString("title"))
        assertNull(result.finalTurnInstruction)
    }

    @Test fun nothingFoundUnderTheHushStaysNotPlaying() {
        hush.start()

        val result = nowPlaying()

        assertEquals(setOf("playing"), result.keys())
        assertEquals(false, result.response.getBoolean("playing"))
    }

    @Test fun noSessionAccessUnderTheHushStaysAnError() {
        hush.start()
        sessions.sessions = null

        assertEquals(setOf("error"), nowPlaying().keys())
    }

    @Test fun aTrackAlreadyPausedWhenTheHushStartedStaysPaused() {
        val quiet = FakeHush(otherAudioPlaying = false).apply { start() }
        sessions.sessions = listOf(spotify(playing = false))

        val result = Music(sessions, quiet, own).nowPlaying.invoke(JSONObject())

        assertEquals(setOf("playing", "app", "title", "artist"), result.keys())
        assertEquals(false, result.response.getBoolean("playing"))
    }

    @Test fun aKeptPauseReportsNotPlaying() {
        hush.start()
        hush.keepPaused()
        sessions.sessions = listOf(spotify(playing = false))

        val result = nowPlaying()

        assertEquals(setOf("playing", "app", "title", "artist"), result.keys())
        assertEquals(false, result.response.getBoolean("playing"))
    }

    @Test fun aLiftedHushReportsNotPlaying() {
        hush.start()
        hush.lift()
        sessions.sessions = listOf(spotify(playing = false))

        val result = nowPlaying()

        assertEquals(setOf("playing", "app", "title", "artist"), result.keys())
        assertEquals(false, result.response.getBoolean("playing"))
    }

    @Test fun ourOwnSessionIsNeverReported() {
        sessions.sessions = listOf(ActiveSession(spotify(playing = true).info.copy(pkg = own), "Jarvis", null))

        assertEquals(setOf("playing"), nowPlaying().keys())
    }
}
