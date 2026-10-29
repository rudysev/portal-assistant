package com.portal.assistant.conversation.tools

import com.portal.assistant.conversation.shoutedWords
import com.portal.assistant.conversation.tools.lookup.LookupHttp
import com.portal.assistant.conversation.tools.lookup.Weather
import com.portal.assistant.conversation.tools.lookup.WebSearch
import com.portal.assistant.conversation.tools.music.FakeMediaSessions
import com.portal.assistant.conversation.tools.music.FakeMusicApps
import com.portal.assistant.conversation.tools.music.Music
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The built-in declarations the system prompt leans on: each tool's rules live only in its declaration, so the rules
 * the prompt dropped must be here, and none of it shouts. A tool that needs an Android `Context` is read through its
 * `DECLARATION` constant, the text its instance declares.
 */
class BuiltinDeclarationsTest {

    private val music = Music(FakeMediaSessions(), FakeMusicApps(), FakeHush(), "com.portal.assistant")
    private val noHttp = LookupHttp { error("not called") }

    private val contextBacked: List<String> = listOf(
        SetTimerTool.DECLARATION,
        ListTimersTool.DECLARATION,
        CancelTimerTool.DECLARATION,
        GetVolumeTool.DECLARATION,
        SetVolumeTool.DECLARATION,
        AdjustVolumeTool.DECLARATION,
        SetMuteTool.DECLARATION,
        GetBrightnessTool.DECLARATION,
        SetBrightnessTool.DECLARATION,
        AdjustBrightnessTool.DECLARATION,
        GetDoNotDisturbTool.DECLARATION,
        SetDoNotDisturbTool.DECLARATION,
        OpenAppTool.DECLARATION,
    )

    private val builtins: List<JSONObject> = listOf(
        GetTimeTool,
        EndConversationTool,
        music.playMusic,
        music.mediaControl,
        music.setRepeat,
        music.nowPlaying,
        Weather(noHttp, { error("not called") }, { Locale.US }, { 0L }),
        WebSearch(noHttp) { error("not called") },
    ).map { it.declaration } + contextBacked.map { JSONObject(it) }

    private fun JSONObject.description() = getString("description")

    private fun JSONObject.param(name: String) =
        getJSONObject("parameters").getJSONObject("properties").getJSONObject(name).getString("description")

    /** Every description in this declaration: the tool's own, then each parameter's. */
    private fun JSONObject.descriptions(): List<String> {
        val properties = getJSONObject("parameters").optJSONObject("properties") ?: JSONObject()
        return listOf(description()) + properties.keys().asSequence().map { param(it) }.toList()
    }

    @Test fun everyBuiltinIsCovered() {
        // The thirteen Context-backed tools plus the eight a JVM test can build: the registry's 21.
        assertEquals(21, builtins.map { it.getString("name") }.toSet().size)
    }

    @Test fun noDeclarationUsesCapitalsForEmphasis() {
        for (declaration in builtins) {
            val shouted = declaration.descriptions().flatMap { shoutedWords(it) }
            assertEquals(declaration.getString("name"), emptyList<String>(), shouted)
        }
    }

    @Test fun endConversationAsksForOneBriefSignOff() {
        assertTrue(EndConversationTool.declaration.description().contains("then give one brief sign-off"))
    }

    @Test fun getTimeIsTheTimeNowAndDeviceContextsIsFromTheStart() {
        val description = GetTimeTool.declaration.description()
        assertTrue(description.contains("Use it for the time now"))
        assertTrue(description.contains("Device Context's time is from when the conversation started"))
    }

    @Test fun listTimersSaysNotToGuessFromSetTimerAndToMatchByLabel() {
        val description = JSONObject(ListTimersTool.DECLARATION).description()
        assertTrue(description.contains("don't guess time left from the portal.set_timer response"))
        assertTrue(description.contains("by its label"))
    }

    @Test fun doNotDisturbExplainsNothingAboutHowItWorks() {
        assertEquals("Turn Do Not Disturb on or off.", JSONObject(SetDoNotDisturbTool.DECLARATION).description())
    }

    @Test fun playMusicAddsTheArtistOnlyForASongClearlyByOneArtist() {
        val query = music.playMusic.declaration.param("query")
        assertTrue(
            query.contains(
                "Add the artist only for a song clearly by one artist (e.g. 'Bohemian Rhapsody Queen'); otherwise " +
                    "use the user's words.",
            ),
        )
        for (text in music.playMusic.declaration.descriptions()) assertFalse(text, text.contains("Spotify Free"))
    }

    @Test fun eachPlayMusicRuleIsStatedOnce() {
        // When to set 'app' is the app parameter's to say, not the tool description's as well.
        assertFalse(music.playMusic.declaration.description().contains("'app'"))
        assertFalse(music.playMusic.declaration.description().contains("'query'"))
    }
}
