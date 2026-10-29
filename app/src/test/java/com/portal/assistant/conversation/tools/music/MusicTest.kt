package com.portal.assistant.conversation.tools.music

import com.portal.assistant.conversation.tools.AppEntry
import com.portal.assistant.conversation.tools.FakeHush
import com.portal.assistant.conversation.tools.ToolOutput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun mediaControl(action: String): ToolOutput =
        music().mediaControl.invoke(JSONObject().put("action", action))

    private fun setRepeat(mode: String?): ToolOutput =
        music().setRepeat.invoke(if (mode == null) JSONObject() else JSONObject().put("mode", mode))

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
                canSetRepeat = true,
            ),
            title = title,
            artist = artist,
        )

    /** The model reads [expected] exactly: same keys and values (key order isn't part of JSON). */
    private fun assertJson(expected: String, output: ToolOutput) =
        assertEquals(JSONObject(expected).asMap(), output.response.asMap())

    private fun Any?.plain(): Any? = when (this) {
        is JSONObject -> asMap()
        is JSONArray -> (0 until length()).map { get(it).plain() }
        else -> this
    }

    private fun JSONObject.asMap(): Map<String, Any?> = keys().asSequence().associateWith { get(it).plain() }

    private fun ToolOutput.keys() = response.keys().asSequence().toSet()

    @Test fun nowPlayingIsDeclaredAsBefore() {
        val tool = music().nowPlaying

        assertEquals("portal.now_playing", tool.name)
        assertEquals(
            JSONObject(
                """{"name":"portal.now_playing",
                   "description":"Get the currently playing (or paused) track: title, artist, and app. hushed=true means the track is playing and only quiet while you listen.",
                   "parameters":{"type":"OBJECT","properties":{},"required":[]}}""",
            ).asMap(),
            tool.declaration.asMap(),
        )
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

    // media_control

    @Test fun mediaControlIsDeclaredAsBefore() {
        val tool = music().mediaControl

        assertEquals("portal.media_control", tool.name)
        assertEquals(
            JSONObject(
                """{"name":"portal.media_control",
                   "description":"Control the current music or media playback.",
                   "parameters":{"type":"OBJECT",
                     "properties":{
                       "action":{"type":"STRING","description":"One of: play (resume), pause, next or previous (track)."}},
                     "required":["action"]}}""",
            ).asMap(),
            tool.declaration.asMap(),
        )
    }

    @Test fun eachActionDrivesThePickedSessionAndMakesAFinalTurn() {
        val alexa = spotify(playing = false).info.copy(index = 0, pkg = "com.amazon.alexa", hasMetadata = false)
        sessions.sessions = listOf(ActiveSession(alexa), ActiveSession(spotify(playing = true).info.copy(index = 1)))

        listOf("play", "pause", "next", "previous").forEach { action ->
            val result = mediaControl(action)

            assertJson("""{"action":"$action","app":"Spotify"}""", result)
            assertEquals(FINAL_TURN, result.finalTurnInstruction)
        }
        assertEquals(listOf("play 1", "pause 1", "next 1", "previous 1"), sessions.calls)
    }

    @Test fun synonymsNameTheirAction() {
        sessions.sessions = listOf(spotify(playing = true))

        val actions = listOf("resume", " Stop ", "skip", "back").map { mediaControl(it).response.getString("action") }

        assertEquals(listOf("play", "pause", "next", "previous"), actions)
        assertEquals(listOf("play 0", "pause 0", "next 0", "previous 0"), sessions.calls)
    }

    @Test fun anUnknownActionIsAnErrorAndNotAFinalTurn() {
        sessions.sessions = listOf(spotify(playing = true))

        val result = mediaControl("rewind")

        assertJson("""{"error":"action must be play, pause, next, or previous"}""", result)
        assertNull(result.finalTurnInstruction)
        assertTrue(sessions.calls.isEmpty())
    }

    @Test fun mediaControlWithNoSessionAccessAsksForNotificationAccess() {
        sessions.sessions = null

        val result = mediaControl("next")

        assertJson(
            """{"error":"media control isn't enabled — grant notification access in Settings"}""",
            result,
        )
        assertNull(result.finalTurnInstruction)
    }

    @Test fun mediaControlWithNothingPlayingIsAnError() {
        val result = mediaControl("next")

        assertJson("""{"error":"nothing is playing"}""", result)
        assertNull(result.finalTurnInstruction)
    }

    @Test fun anActionNoSessionSupportsIsAnError() {
        sessions.sessions = listOf(ActiveSession(spotify(playing = true).info.copy(canNext = false)))

        val result = mediaControl("next")

        assertJson("""{"error":"no media session supports that action"}""", result)
        assertNull(result.finalTurnInstruction)
        assertTrue(sessions.calls.isEmpty())
    }

    @Test fun ourOwnSessionIsNeverDriven() {
        sessions.sessions = listOf(ActiveSession(spotify(playing = true).info.copy(pkg = own)))

        val result = mediaControl("next")

        assertJson("""{"error":"no media session supports that action"}""", result)
        assertTrue(sessions.calls.isEmpty())
    }

    // pause under the hush

    @Test fun aPauseUnderAHeldHushKeepsThePauseWithoutTheSession() {
        hush.start()
        sessions.sessions = listOf(spotify(playing = false))

        val result = mediaControl("pause")

        assertEquals(1, hush.keepPausedCalls)
        assertJson("""{"action":"pause"}""", result)
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
        assertTrue(sessions.calls.isEmpty())
    }

    @Test fun aStopUnderAHeldHushNeedsNoSessionAccess() {
        hush.start()
        sessions.sessions = null

        val result = mediaControl(" Stop ")

        assertEquals(1, hush.keepPausedCalls)
        assertJson("""{"action":"pause"}""", result)
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
    }

    @Test fun aPauseWhoseKeptPauseIsDeniedPausesTheSession() {
        val denied = FakeHush(keepPausedGranted = false).apply { start() }
        sessions.sessions = listOf(spotify(playing = false))

        val result = Music(sessions, apps, denied, own).mediaControl.invoke(JSONObject().put("action", "pause"))

        assertEquals(1, denied.keepPausedCalls)
        assertEquals(listOf("pause 0"), sessions.calls)
        assertJson("""{"action":"pause","app":"Spotify"}""", result)
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
    }

    @Test fun aPauseWithNoHushHeldPausesTheSession() {
        sessions.sessions = listOf(spotify(playing = true))

        val result = mediaControl("pause")

        assertEquals(listOf("pause 0"), sessions.calls)
        assertJson("""{"action":"pause","app":"Spotify"}""", result)
        assertEquals(FINAL_TURN, result.finalTurnInstruction)
    }

    @Test fun aPauseAfterTheHushWasLostPausesTheSession() {
        hush.start()
        hush.loseFocus()
        sessions.sessions = listOf(spotify(playing = true))

        val result = mediaControl("pause")

        assertEquals(listOf("pause 0"), sessions.calls)
        assertJson("""{"action":"pause","app":"Spotify"}""", result)
    }

    @Test fun everyOtherActionUnderAHeldHushGoesToTheSession() {
        hush.start()
        sessions.sessions = listOf(spotify(playing = false))

        listOf("play", "next", "previous").forEach { mediaControl(it) }

        assertEquals(0, hush.keepPausedCalls)
        assertEquals(listOf("play 0", "next 0", "previous 0"), sessions.calls)
    }

    // set_repeat

    @Test fun setRepeatIsDeclaredAsBefore() {
        val tool = music().setRepeat

        assertEquals("portal.set_repeat", tool.name)
        assertEquals(
            JSONObject(
                """{"name":"portal.set_repeat",
                   "description":"Set repeat on the music now playing.",
                   "parameters":{"type":"OBJECT",
                     "properties":{
                       "mode":{"type":"STRING","description":"One of: one (repeat this song), all (repeat the album, playlist or queue) or off."}},
                     "required":["mode"]}}""",
            ).asMap(),
            tool.declaration.asMap(),
        )
    }

    @Test fun eachRepeatModeIsSetOnTheSessionAndMakesAFinalTurn() {
        sessions.sessions = listOf(spotify(playing = true))

        listOf("one" to "one", "all" to "all", "off" to "off", " None " to "off").forEach { (word, mode) ->
            val result = setRepeat(word)

            assertJson("""{"repeat":"$mode","app":"Spotify"}""", result)
            assertEquals(FINAL_TURN, result.finalTurnInstruction)
        }
        assertEquals(listOf("repeat one 0", "repeat all 0", "repeat off 0", "repeat off 0"), sessions.calls)
    }

    @Test fun aBadRepeatModeIsAnError() {
        sessions.sessions = null // the mode is checked before session access

        listOf("stop", "", null).forEach { mode ->
            val result = setRepeat(mode)

            assertJson("""{"error":"repeat must be one, all, or off"}""", result)
            assertNull(result.finalTurnInstruction)
        }
    }

    @Test fun setRepeatWithNoSessionAccessAsksForNotificationAccess() {
        sessions.sessions = null

        val result = setRepeat("one")

        assertJson(
            """{"error":"media control isn't enabled — grant notification access in Settings"}""",
            result,
        )
        assertNull(result.finalTurnInstruction)
    }

    @Test fun setRepeatWithNothingPlayingIsAnError() {
        val result = setRepeat("one")

        assertJson("""{"error":"nothing is playing"}""", result)
        assertNull(result.finalTurnInstruction)
    }

    @Test fun repeatUnsupportedByTheAppIsAnError() {
        sessions.sessions = listOf(ActiveSession(spotify(playing = true).info.copy(canSetRepeat = false)))

        val result = setRepeat("all")

        assertJson("""{"error":"repeat isn't available on the current app"}""", result)
        assertNull(result.finalTurnInstruction)
        assertTrue(sessions.calls.isEmpty())
    }

    @Test fun aFailedRepeatCallIsAnError() {
        sessions.sessions = listOf(spotify(playing = true))
        sessions.repeatFailure = IllegalStateException("session gone")

        val result = setRepeat("one")

        assertJson("""{"error":"couldn't set repeat on Spotify: session gone"}""", result)
        assertNull(result.finalTurnInstruction)
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

    @Test fun playMusicIsDeclaredAsBefore() {
        val tool = music().playMusic

        assertEquals("portal.play_music", tool.name)
        assertEquals(
            JSONObject(
                """{"name":"portal.play_music",
                   "description":"Start playing a song, artist, album or playlist on a music app. If the result has 'searchUnsupported', the app was only opened: tell the user you opened it rather than claiming it's playing. For resume/pause/skip of the current track use portal.media_control instead.",
                   "parameters":{"type":"OBJECT",
                     "properties":{
                       "query":{"type":"STRING","description":"What to play, e.g. 'Here Comes the Sun', 'Taylor Swift', 'some jazz' or 'Discover Weekly'. Add the artist only for a song clearly by one artist (e.g. 'Bohemian Rhapsody Queen'); otherwise use the user's words. Don't add words like 'live', 'cover', 'remix' or 'karaoke' unless the user asked for that."},
                       "app":{"type":"STRING","description":"The music app to play on, only when the user names one (e.g. 'play Zanarkand on TIDAL' -> 'TIDAL'). Omit it to use their default music app."},
                       "type":{"type":"STRING","description":"What kind to play, only when the user says so: 'song', 'artist', 'album' or 'playlist' (e.g. 'play the album Thriller' -> 'album'). Omit it otherwise."}},
                     "required":["query"]}}""",
            ).asMap(),
            tool.declaration.asMap(),
        )
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
        apps.installed = mapOf(tidal.pkg to tidal.label) // a saved default is trusted, not looked up in a list
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
