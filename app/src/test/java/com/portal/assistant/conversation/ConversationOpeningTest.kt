package com.portal.assistant.conversation

import com.portal.assistant.conversation.backend.Backends
import com.portal.assistant.conversation.tools.lookup.LookupHttp
import com.portal.assistant.conversation.tools.lookup.Weather
import com.portal.assistant.conversation.tools.lookup.WebSearch
import com.portal.assistant.gemini.GeminiModel
import com.portal.assistant.system.AppPrefs
import com.portal.assistant.system.DeviceLocation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * What [ConversationOpening] decides: whether a backend choice refuses ([ConversationOpening.refusalFor]), and from
 * a [Trigger] and a snapshot of the conversation's inputs, resume or fresh, what is replayed, voice query, Listening
 * tone, first text, and what the backend is told. That a refused trigger opens nothing is [AssistantEngineTest]'s.
 * Prompt wording and truncation edges are [SessionPromptTest], [ResumeContextTest] and [SystemContextTest]'s.
 */
class ConversationOpeningTest {

    // --- Refusal: no credential, no conversation ---

    @Test fun refusesGeminiWithNoApiKey() {
        val refusal = ConversationOpening.refusalFor(geminiChoice(apiKey = ""))
        assertEquals("Jarvis isn’t set up yet — a Gemini API key is missing.", refusal?.notice)
    }

    @Test fun refusesGeminiWithABlankApiKey() {
        assertEquals(
            "Jarvis isn’t set up yet — a Gemini API key is missing.",
            ConversationOpening.refusalFor(geminiChoice(apiKey = "   "))?.notice,
        )
    }

    @Test fun refusesLocalWithNoHostAddress() {
        assertEquals(
            "Jarvis isn’t set up yet — add your local server address in Settings.",
            ConversationOpening.refusalFor(localChoice(hostUrl = null))?.notice,
        )
    }

    @Test fun refusesLocalWithABlankHostAddress() {
        assertEquals(
            "Jarvis isn’t set up yet — add your local server address in Settings.",
            ConversationOpening.refusalFor(localChoice(hostUrl = ""))?.notice,
        )
    }

    @Test fun noRefusalWithACredential() {
        assertNull(ConversationOpening.refusalFor(geminiChoice(apiKey = "user-key")))
        assertNull(ConversationOpening.refusalFor(localChoice(hostUrl = "wss://192.168.1.5:8080")))
    }

    // --- Resume or fresh ---

    @Test fun micTapWithATranscriptOnScreenResumesIt() {
        val onScreen = listOf(Turn(Role.USER, "What's the capital of France?"), Turn(Role.MODEL, "Paris."))
        val opening = open(Trigger.MicTap, inputs().copy(onScreenTurns = onScreen))
        assertEquals(TranscriptSeed.Resume(turnsShown = onScreen, turnsReplayed = onScreen), opening.transcriptSeed)
        assertTrue(opening.sessionPrompt.contains("User: What's the capital of France?\nAssistant: Paris."))
    }

    @Test fun everyTriggerWithNothingOnScreenStartsFresh() {
        for (trigger in allTriggers) {
            assertEquals(
                trigger.logLabel,
                TranscriptSeed.Fresh,
                open(trigger, inputs().copy(onScreenTurns = emptyList())).transcriptSeed,
            )
        }
    }

    @Test fun wakeWordAndSuggestionChipStartFreshEvenWithATranscriptOnScreen() {
        val onScreen = listOf(Turn(Role.USER, "What's the capital of France?"), Turn(Role.MODEL, "Paris."))
        for (trigger in listOf(Trigger.WakeWord("jarvis"), Trigger.SuggestionChip("What's the weather?"))) {
            val opening = open(trigger, inputs().copy(onScreenTurns = onScreen))
            assertEquals(trigger.logLabel, TranscriptSeed.Fresh, opening.transcriptSeed)
            assertFalse(trigger.logLabel, opening.sessionPrompt.contains("Paris."))
        }
    }

    @Test fun aLongResumeShowsEveryTurnButReplaysOnlyTheRecentOnesWithinTheBudget() {
        // 4,000 chars of budget: the two newest 1,500-char turns fit (3,000); the third would make 4,500.
        val oldest = Turn(Role.USER, "a".repeat(1_500))
        val middle = Turn(Role.MODEL, "b".repeat(1_500))
        val previous = Turn(Role.USER, "c".repeat(1_500))
        val latest = Turn(Role.MODEL, "d".repeat(1_500))
        val onScreen = listOf(oldest, middle, previous, latest)
        val opening = open(Trigger.MicTap, inputs().copy(onScreenTurns = onScreen))
        assertEquals(
            TranscriptSeed.Resume(turnsShown = onScreen, turnsReplayed = listOf(previous, latest)),
            opening.transcriptSeed,
        )
        assertTrue(opening.sessionPrompt.contains(latest.text))
        assertFalse(opening.sessionPrompt.contains(middle.text))
    }

    // --- Voice query and first text ---

    @Test fun wakeWordAndMicTapAreVoiceQueriesWithNoFirstText() {
        for (trigger in listOf(Trigger.WakeWord("jarvis"), Trigger.MicTap)) {
            val opening = open(trigger, inputs())
            assertTrue(trigger.logLabel, opening.voiceQuery)
            assertNull(trigger.logLabel, opening.firstText)
        }
    }

    @Test fun aSuggestionChipSendsItsTextAsTheFirstTurnAndIsNotAVoiceQuery() {
        val opening = open(Trigger.SuggestionChip("Set a 10-minute timer"), inputs())
        assertFalse(opening.voiceQuery)
        assertEquals("Set a 10-minute timer", opening.firstText)
    }

    // --- Listening tone ---

    @Test fun theListeningTonePlaysForAVoiceQueryWithTheSettingOn() {
        assertTrue(open(Trigger.WakeWord("jarvis"), inputs().copy(listeningToneEnabled = true)).listeningTonePlays)
        assertTrue(open(Trigger.MicTap, inputs().copy(listeningToneEnabled = true)).listeningTonePlays)
    }

    @Test fun theListeningToneIsSilentWithTheSettingOff() {
        for (trigger in allTriggers) {
            assertFalse(trigger.logLabel, open(trigger, inputs().copy(listeningToneEnabled = false)).listeningTonePlays)
        }
    }

    @Test fun theListeningToneIsSilentForASuggestionChip() {
        // A chip's query is sent as text, so there is nothing to tell the user to say.
        assertFalse(
            open(
                Trigger.SuggestionChip("What's the weather?"),
                inputs().copy(listeningToneEnabled = true),
            ).listeningTonePlays,
        )
    }

    // --- What Jarvis is told up front: time and location ---

    @Test fun aLocationOverrideBeatsTheIpGeoGuess() {
        val prompt = open(
            Trigger.WakeWord("jarvis"),
            inputs().copy(locationOverride = "Lisbon, Portugal", deviceLocation = seattle),
        ).sessionPrompt
        assertTrue(prompt.contains("Location: Lisbon, Portugal."))
        assertFalse(prompt.contains("Seattle"))
    }

    @Test fun theIpGeoGuessIsUsedWithNoOverride() {
        val prompt = open(
            Trigger.WakeWord("jarvis"),
            inputs().copy(locationOverride = null, deviceLocation = seattle),
        ).sessionPrompt
        assertTrue(prompt.lines().contains("- Location: Seattle, Washington, United States."))
        assertFalse(prompt.contains("lat "))
    }

    @Test fun theLocationIsUnknownWithNeitherOverrideNorIpGeo() {
        val prompt = open(
            Trigger.WakeWord("jarvis"),
            inputs().copy(locationOverride = null, deviceLocation = null),
        ).sessionPrompt
        assertTrue(prompt.contains("Location: unknown — if a question needs a location, ask the user."))
    }

    @Test fun theTimeIsTheSnapshotsClockInItsZone() {
        val prompt = open(Trigger.WakeWord("jarvis"), inputs()).sessionPrompt
        val timeLine = prompt.lines().single { it.startsWith("- Time at conversation start: ") }
        assertTrue(timeLine, timeLine.contains("2026"))
        assertTrue(timeLine, timeLine.endsWith("(America/Los_Angeles)."))
    }

    @Test fun enabledExternalProvidersAreDescribedInThePrompt() {
        val line = "- Kasa: control the smart plugs in the house."
        assertTrue(
            open(
                Trigger.WakeWord("jarvis"),
                inputs().copy(externalPromptLines = listOf(line)),
            ).sessionPrompt.contains(line),
        )
    }

    // --- The backend's session config ---

    @Test fun theBackendConfigCarriesTheSessionInputs() {
        val declarations = listOf(JSONObject().put("name", "portal.get_time"))
        val opening = open(
            Trigger.MicTap,
            inputs().copy(
                backendChoice = geminiChoice(apiKey = "user-key"),
                modelId = "gemini-2.5-flash-native-audio-latest",
                speakerId = "Kore",
                toolDeclarations = declarations,
            ),
        )
        val config = opening.backendConfig
        assertEquals("user-key", config.credential)
        assertEquals(AppPrefs.VoiceBackendKind.GEMINI, config.kind)
        assertEquals("gemini-2.5-flash-native-audio-latest", config.model)
        assertEquals("Kore", config.speaker)
        assertEquals(opening.sessionPrompt, config.systemPrompt)
        assertEquals(declarations, config.functionDeclarations)
    }

    @Test fun serverDefaultSendsNoSpeaker() {
        assertNull(open(Trigger.MicTap, inputs().copy(speakerId = null)).backendConfig.speaker)
    }

    @Test fun aLocalBackendGetsItsHostAddressAsTheCredential() {
        val config = open(
            Trigger.MicTap,
            inputs().copy(backendChoice = localChoice(hostUrl = "wss://192.168.1.5:8080")),
        ).backendConfig
        assertEquals("wss://192.168.1.5:8080", config.credential)
        assertEquals(AppPrefs.VoiceBackendKind.LOCAL, config.kind)
    }

    // --- Lookups: decided at opening; the declared tools follow (and the Google Search line, its only prompt line) ---

    @Test fun aModelWithGoogleSearchGetsGoogleSearchOnlyWithOrWithoutASearchKey() {
        for (searchKey in listOf(true, false)) {
            val label = "searchKey=$searchKey"
            val opening = open(Trigger.MicTap, lookupInputs(googleSearch = true, searchKey = searchKey))
            assertEquals(label, setOf(Lookup.GOOGLE_SEARCH), opening.lookups)
            assertTrue(label, opening.backendConfig.googleSearch)
            assertEquals(label, listOf("portal.get_time"), declaredNames(opening))
            assertTrue(label, opening.sessionPrompt.contains(GOOGLE_SEARCH_LINE))
            assertFalse(label, opening.sessionPrompt.contains("portal."))
            assertTrue(label, opening.sessionPrompt.contains(CLOSING_RULE))
        }
    }

    @Test fun aModelWithoutGoogleSearchAndASearchKeyGetsWeatherAndWebSearch() {
        val opening = open(Trigger.MicTap, lookupInputs(googleSearch = false, searchKey = true))
        assertEquals(setOf(Lookup.WEATHER, Lookup.WEB_SEARCH), opening.lookups)
        assertFalse(opening.backendConfig.googleSearch)
        assertEquals(listOf("portal.get_time", "portal.get_weather", "portal.web_search"), declaredNames(opening))
        assertFalse(opening.sessionPrompt.contains("Google Search"))
        // Their declarations say when to call them; the prompt names no tool.
        assertFalse(opening.sessionPrompt.contains("portal."))
        assertTrue(opening.sessionPrompt.contains(CLOSING_RULE))
    }

    @Test fun aModelWithoutGoogleSearchAndNoSearchKeyGetsWeatherOnly() {
        val opening = open(Trigger.MicTap, lookupInputs(googleSearch = false, searchKey = false))
        assertEquals(setOf(Lookup.WEATHER), opening.lookups)
        assertFalse(opening.backendConfig.googleSearch)
        assertEquals(listOf("portal.get_time", "portal.get_weather"), declaredNames(opening))
        assertFalse(opening.sessionPrompt.contains("Google Search"))
        assertFalse(opening.sessionPrompt.contains("portal."))
        assertTrue(opening.sessionPrompt.contains(CLOSING_RULE))
    }

    @Test fun aLookupWhoseToolIsNotRegisteredIsNeitherDeclaredNorNamed() {
        // Until its tool is registered, a model without Google Search has no lookup: only the shared rule.
        val opening = open(
            Trigger.MicTap,
            lookupInputs(googleSearch = false, searchKey = true).copy(toolDeclarations = listOf(getTime)),
        )
        assertEquals(emptySet<Lookup>(), opening.lookups)
        assertEquals(listOf("portal.get_time"), declaredNames(opening))
        assertFalse(opening.sessionPrompt.contains("Google Search"))
        assertFalse(opening.sessionPrompt.contains("portal."))
        assertTrue(opening.sessionPrompt.contains(CLOSING_RULE))
    }

    @Test fun gemini38DeclaresTheWeatherToolAndGemini25DoesNot() {
        // The real Weather declaration, as the tool registry registers it, against the real Model catalog.
        val weather = Weather(LookupHttp { error("not called") }, { error("not called") }, { Locale.US }, { 0L })
        val registered = listOf(getTime, weather.declaration)
        fun openOn(modelId: String) = open(
            Trigger.MicTap,
            inputs().copy(
                modelId = modelId,
                modelOffersGoogleSearch = Backends.offersGoogleSearch(modelId),
                toolDeclarations = registered,
            ),
        )

        val on38 = openOn("gemini-3.8-live")
        assertEquals(listOf("portal.get_time", "portal.get_weather"), declaredNames(on38))

        val on25 = openOn(GeminiModel.DEFAULT.id)
        assertEquals(listOf("portal.get_time"), declaredNames(on25))
    }

    @Test fun gemini38WithASearchKeyDeclaresWebSearchAndOtherwiseItIsNot() {
        // The real lookup declarations, as the tool registry registers them, against the real Model catalog.
        val weather = Weather(LookupHttp { error("not called") }, { error("not called") }, { Locale.US }, { 0L })
        val webSearch = WebSearch(LookupHttp { error("not called") }) { error("not called") }
        val registered = listOf(getTime, weather.declaration, webSearch.declaration)
        fun openOn(modelId: String, searchKey: Boolean) = open(
            Trigger.MicTap,
            inputs().copy(
                modelId = modelId,
                modelOffersGoogleSearch = Backends.offersGoogleSearch(modelId),
                searchKeyPresent = searchKey,
                toolDeclarations = registered,
            ),
        )

        val withKey = openOn("gemini-3.8-live", searchKey = true)
        assertEquals(listOf("portal.get_time", "portal.get_weather", "portal.web_search"), declaredNames(withKey))

        for ((label, opening) in listOf(
            "3.8 without a key" to openOn("gemini-3.8-live", searchKey = false),
            "2.5 with a key" to openOn(GeminiModel.DEFAULT.id, searchKey = true),
        )) {
            assertFalse(label, "portal.web_search" in declaredNames(opening))
        }
    }

    @Test fun theLocalBackendGetsNoLookupsWhateverTheModelOrSearchKey() {
        for (googleSearch in listOf(true, false)) {
            for (searchKey in listOf(true, false)) {
                val label = "googleSearch=$googleSearch searchKey=$searchKey"
                val opening = open(
                    Trigger.MicTap,
                    lookupInputs(googleSearch, searchKey).copy(backendChoice = localChoice("wss://192.168.1.5:8080")),
                )
                assertEquals(label, emptySet<Lookup>(), opening.lookups)
                assertFalse(label, opening.backendConfig.googleSearch)
                assertEquals(label, listOf("portal.get_time"), declaredNames(opening))
                assertFalse(label, opening.sessionPrompt.contains("Google Search"))
                assertFalse(label, opening.sessionPrompt.contains("portal."))
                assertTrue(label, opening.sessionPrompt.contains(CLOSING_RULE))
            }
        }
    }

    // --- helpers ---

    private val getTime = JSONObject().put("name", "portal.get_time")

    /** Every tool registered, both lookup tools included, so only the opening decides which are declared. */
    private fun lookupInputs(googleSearch: Boolean, searchKey: Boolean) = inputs().copy(
        modelOffersGoogleSearch = googleSearch,
        searchKeyPresent = searchKey,
        toolDeclarations = listOf(
            getTime,
            JSONObject().put("name", "portal.get_weather"),
            JSONObject().put("name", "portal.web_search"),
        ),
    )

    private fun declaredNames(opening: Opening) =
        opening.backendConfig.functionDeclarations.map { it.getString("name") }

    private val seattle = DeviceLocation("Seattle", "Washington", "United States", lat = 47.6062, lon = -122.3321)

    private fun open(trigger: Trigger, inputs: OpeningInputs): Opening = ConversationOpening.open(trigger, inputs)

    private val allTriggers =
        listOf(Trigger.WakeWord("jarvis"), Trigger.MicTap, Trigger.SuggestionChip("What's the weather?"))

    private fun geminiChoice(apiKey: String) = Backends.resolveChoice(
        selected = AppPrefs.VoiceBackendKind.GEMINI,
        storedApiKey = apiKey,
        storedLocalWssUrl = null,
        devApiKey = "",
    )

    private fun localChoice(hostUrl: String?) = Backends.resolveChoice(
        selected = AppPrefs.VoiceBackendKind.LOCAL,
        storedApiKey = null,
        storedLocalWssUrl = hostUrl,
        devApiKey = "",
    )

    /** The baseline inputs; a test changes only what it is about, with copy(). */
    private fun inputs() = OpeningInputs(
        backendChoice = geminiChoice(apiKey = "user-key"),
        modelId = "gemini-2.5-flash-native-audio-latest",
        modelOffersGoogleSearch = true,
        searchKeyPresent = false,
        speakerId = null,
        listeningToneEnabled = true,
        now = ZonedDateTime.of(2026, 10, 21, 9, 30, 0, 0, ZoneId.of("America/Los_Angeles")),
        locationOverride = null,
        deviceLocation = null,
        onScreenTurns = emptyList(),
        toolDeclarations = emptyList(),
        externalPromptLines = emptyList(),
    )

    private companion object {
        const val GOOGLE_SEARCH_LINE = "- Google Search: use it for live information."
        const val CLOSING_RULE = "if you can't, say \"I can't check that\" instead of guessing."
    }
}
