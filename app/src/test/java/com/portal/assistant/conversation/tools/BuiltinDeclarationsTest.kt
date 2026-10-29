package com.portal.assistant.conversation.tools

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
 * the prompt dropped must be here, and none of it shouts. Covers the built-ins a JVM test can build (the rest need an
 * Android `Context`).
 */
class BuiltinDeclarationsTest {

    private val music = Music(FakeMediaSessions(), FakeMusicApps(), FakeHush(), "com.portal.assistant")
    private val noHttp = LookupHttp { error("not called") }

    private val builtins: List<Tool> = listOf(
        GetTimeTool,
        EndConversationTool,
        music.playMusic,
        music.mediaControl,
        music.setRepeat,
        music.nowPlaying,
        Weather(noHttp, { error("not called") }, { Locale.US }, { 0L }),
        WebSearch(noHttp) { error("not called") },
    )

    private fun Tool.description() = declaration.getString("description")

    private fun Tool.param(name: String) =
        declaration.getJSONObject("parameters").getJSONObject("properties").getJSONObject(name)
            .getString("description")

    /** Every description in [declaration]: the tool's own, then each parameter's. */
    private fun Tool.descriptions(): List<String> {
        val properties = declaration.getJSONObject("parameters").optJSONObject("properties") ?: JSONObject()
        return listOf(description()) + properties.keys().asSequence().map { param(it) }.toList()
    }

    @Test fun noDeclarationUsesCapitalsForEmphasis() {
        // Acronyms are fine ("TIDAL" is an app's name); a shouted word like ONLY, ARTIST or KIND is not.
        val shouting = Regex("""\b[A-Z]{3,}\b""")
        val allowed = setOf("TIDAL")
        for (tool in builtins) {
            val shouted = tool.descriptions().flatMap { text -> shouting.findAll(text).map { it.value }.toList() }
            assertEquals(tool.name, emptyList<String>(), shouted.filterNot { it in allowed })
        }
    }

    @Test fun endConversationAsksForOneBriefSignOff() {
        assertTrue(EndConversationTool.description().contains("then give one brief sign-off"))
    }

    @Test fun getTimeIsTheTimeNowAndDeviceContextsIsFromTheStart() {
        assertEquals(
            "Current local date and time. Use it for the time now; Device Context's time is from when the " +
                "conversation started.",
            GetTimeTool.description(),
        )
    }

    @Test fun playMusicAddsTheArtistOnlyForASongClearlyByOneArtist() {
        val query = music.playMusic.param("query")
        assertTrue(
            query.contains(
                "Add the artist only for a song clearly by one artist (e.g. 'Bohemian Rhapsody Queen'); otherwise " +
                    "use the user's words.",
            ),
        )
        for (text in music.playMusic.descriptions()) assertFalse(text, text.contains("Spotify Free"))
    }

    @Test fun eachPlayMusicRuleIsStatedOnce() {
        // When to set 'app' is the app parameter's to say, not the tool description's as well.
        assertFalse(music.playMusic.description().contains("'app'"))
        assertFalse(music.playMusic.description().contains("'query'"))
    }
}
