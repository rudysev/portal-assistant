package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.AppEntry
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
    private val apps = FakeMusicApps()
    private val hush = FakeHush()

    private fun music() = Music(sessions, apps, hush, own)

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

        val result = Music(sessions, apps, quiet, own).nowPlaying.invoke(JSONObject())

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

    // --- play_music ---

    private val spotifyApp = AppEntry("Spotify", "com.facebook.aloha.spotifystandalone")
    private val tidal = AppEntry("TIDAL", "com.aspiro.tidal")
    private val player = AppEntry("Portal Player", "com.facebook.alohaservices.player2")

    private fun ToolOutput.fields() = response.keys().asSequence().associateWith { response.get(it) }

    private fun play(query: String, app: String? = null, type: String? = null): ToolOutput {
        val args = JSONObject().put("query", query)
        app?.let { args.put("app", it) }
        type?.let { args.put("type", it) }
        return music().playMusic.invoke(args)
    }

    @Test fun playMusicIsDeclaredUnderItsName() {
        val tool = music().playMusic

        assertEquals("portal.play_music", tool.name)
        assertEquals("portal.play_music", tool.declaration.getString("name"))
        assertEquals("[\"query\"]", tool.declaration.getJSONObject("parameters").getJSONArray("required").toString())
    }

    @Test fun aBlankQueryIsAnErrorAndStartsNothing() {
        apps.music = listOf(spotifyApp)

        val result = play("   ")

        assertEquals(mapOf("error" to "no song or artist specified"), result.fields())
        assertNull(result.finalTurnInstruction)
        assertEquals(emptyList<String>(), apps.fired)
    }

    @Test fun aNamedAppPlaysThereOverTheDefault() {
        apps.default = spotifyApp.pkg
        apps.music = listOf(spotifyApp, tidal)
        apps.playFromSearchPkgs = setOf(tidal.pkg)

        val result = play(" Zanarkand ", app = "tidal", type = "Albums")

        assertEquals(mapOf("playing" to true, "query" to "Zanarkand", "app" to "TIDAL"), result.fields())
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
        assertEquals(listOf("playFromSearch com.aspiro.tidal Zanarkand ALBUM"), apps.fired)
    }

    @Test fun aNamedAppThatIsNotInstalledIsAnError() {
        apps.music = listOf(spotifyApp)

        val result = play("Zanarkand", app = "Pandora")

        assertEquals(mapOf("error" to "can't play music on \"Pandora\""), result.fields())
        assertNull(result.finalTurnInstruction)
        assertEquals(emptyList<String>(), apps.fired)
    }

    @Test fun noAppNamedPlaysOnTheDefaultUnderItsLabel() {
        apps.default = tidal.pkg
        apps.searchPlayable = listOf(tidal)
        apps.playFromSearchPkgs = setOf(tidal.pkg)

        val result = play("Zanarkand")

        assertEquals(mapOf("playing" to true, "query" to "Zanarkand", "app" to "TIDAL"), result.fields())
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
        assertEquals(listOf("playFromSearch com.aspiro.tidal Zanarkand any"), apps.fired)
    }

    @Test fun noMusicAppIsAnErrorAskingWhichAppToUse() {
        val result = play("Zanarkand")

        assertEquals(
            mapOf("error" to "no default music app set — say which app to play on, or pick a favorite in Settings"),
            result.fields(),
        )
        assertNull(result.finalTurnInstruction)
        assertEquals(emptyList<String>(), apps.fired)
    }

    @Test fun spotifyPlaysThroughItsSearchDeepLink() {
        apps.music = listOf(spotifyApp)
        apps.playFromSearchPkgs = setOf(spotifyApp.pkg)

        val result = play("Bohemian Rhapsody Queen")

        assertEquals(
            mapOf("playing" to true, "query" to "Bohemian Rhapsody Queen", "app" to "Spotify"),
            result.fields(),
        )
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
        assertEquals(listOf("deepLink com.facebook.aloha.spotifystandalone Bohemian Rhapsody Queen"), apps.fired)
    }

    @Test fun aSpotifyDeepLinkThatFailsIsAnError() {
        apps.music = listOf(spotifyApp)
        apps.deepLinkResult = Start.Failed("boom")

        val result = play("Queen")

        assertEquals(mapOf("error" to "couldn't start Spotify: boom"), result.fields())
        assertNull(result.finalTurnInstruction)
    }

    @Test fun playFromSearchFailingToStartFallsBackToOpeningTheApp() {
        apps.music = listOf(tidal)
        apps.playFromSearchPkgs = setOf(tidal.pkg)
        apps.playFromSearchResult = Start.Failed("boom")

        val result = play("Zanarkand", type = "song")

        assertEquals(
            mapOf("opened" to true, "searchUnsupported" to true, "query" to "Zanarkand", "app" to "TIDAL"),
            result.fields(),
        )
        assertNull(result.finalTurnInstruction)
        assertEquals(listOf("playFromSearch com.aspiro.tidal Zanarkand SONG", "launch com.aspiro.tidal"), apps.fired)
    }

    @Test fun anAppWithoutPlayFromSearchIsOnlyOpened() {
        apps.music = listOf(player)

        val result = play("Zanarkand")

        assertEquals(
            mapOf("opened" to true, "searchUnsupported" to true, "query" to "Zanarkand", "app" to "Portal Player"),
            result.fields(),
        )
        assertNull(result.finalTurnInstruction)
        assertEquals(listOf("launch com.facebook.alohaservices.player2"), apps.fired)
    }

    @Test fun anAppThatFailsToOpenIsAnError() {
        apps.music = listOf(player)
        apps.launchResult = Start.Failed("boom")

        val result = play("Zanarkand")

        assertEquals(mapOf("error" to "couldn't open Portal Player: boom"), result.fields())
        assertNull(result.finalTurnInstruction)
    }

    @Test fun anAppWithNoLaunchActivityIsAnError() {
        apps.music = listOf(player)
        apps.launchResult = Start.Failed(null)

        val result = play("Zanarkand")

        assertEquals(mapOf("error" to "couldn't open Portal Player"), result.fields())
        assertNull(result.finalTurnInstruction)
    }

    private companion object {
        const val FINAL_TURN = "The conversation ends after your reply: briefly confirm, answer anything else the " +
            "user asked, and don't ask a follow-up question."
    }
}
