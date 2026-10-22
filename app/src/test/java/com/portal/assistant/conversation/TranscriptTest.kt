package com.portal.assistant.conversation

import com.portal.assistant.conversation.Transcript.Event.AudioPlayed
import com.portal.assistant.conversation.Transcript.Event.AudioReceived
import com.portal.assistant.conversation.Transcript.Event.Interrupted
import com.portal.assistant.conversation.Transcript.Event.JarvisText
import com.portal.assistant.conversation.Transcript.Event.ListeningStarted
import com.portal.assistant.conversation.Transcript.Event.UserText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for the pure [Transcript]: each folds an event sequence the way the engine sends it and asserts on the
 * turns (and `===` when nothing visible changed). Played audio is a plain value in the events, so the reveal is
 * driven deterministically.
 */
class TranscriptTest {

    private fun Transcript.after(vararg events: Transcript.Event) = events.fold(this) { t, e -> t.on(e) }

    // Compare by role+text (ids are global-monotonic and not meaningful to assert).
    private fun Transcript.pairs() = turns.map { it.role to it.text }

    private fun Transcript.latestRevealed() = turns.last().revealedWords

    private val tenWords = "one two three four five six seven eight nine ten"

    @Test fun toolCallAnswerRevealsFromItsFirstWordAfterALongRevealedAnswer() {
        // A tool-call turn enters SPEAKING on ToolCallReceived, before any model-generating message — so the
        // reveal must restart at the listening boundary, not on the first "generating" of the turn.
        val afterLongAnswer = Transcript().after(
            ListeningStarted(playedBytes = 0),
            UserText("tell me a story"),
            JarvisText(tenWords, SPEAKING),
            AudioReceived(bytes = 100, playedBytes = 0),
            AudioPlayed(playedBytes = 100),
        )
        assertEquals(10, afterLongAnswer.latestRevealed())

        val confirmation = afterLongAnswer.after(
            ListeningStarted(playedBytes = 100),
            UserText("set a ten minute timer"),
            AudioReceived(bytes = 100, playedBytes = 100),
            JarvisText("Timer set for ten minutes.", SPEAKING),
            AudioPlayed(playedBytes = 100),
        )
        assertEquals(Role.MODEL to "Timer set for ten minutes.", confirmation.pairs().last())
        assertEquals(0, confirmation.latestRevealed())

        assertEquals(3, confirmation.after(AudioPlayed(playedBytes = 150)).latestRevealed()) // half of 5 words
    }

    // ---- turns ---------------------------------------------------------------------------------------

    @Test fun emptyByDefault() {
        assertEquals(emptyList<Pair<Role, String>>(), Transcript().pairs())
    }

    @Test fun userDeltasGrowOneTurn() {
        val t = Transcript().after(UserText("what's "), UserText("the weather"))
        assertEquals(listOf(Role.USER to "what's the weather"), t.pairs())
    }

    @Test fun jarvisDeltasStartTheirOwnTurn() {
        val t = Transcript().after(UserText("hi"), JarvisText("hello ", SPEAKING), JarvisText("there", SPEAKING))
        assertEquals(listOf(Role.USER to "hi", Role.MODEL to "hello there"), t.pairs())
    }

    @Test fun listeningStartsAFreshUserTurnEvenWithNoJarvisTurnBetween() {
        // Two separate user utterances with no answer between them (a re-listen) must not merge.
        val t = Transcript().after(UserText("first"), ListeningStarted(0), UserText("second"))
        assertEquals(listOf(Role.USER to "first", Role.USER to "second"), t.pairs())
    }

    @Test fun listeningStartsAFreshJarvisTurnEvenWhenJarvisSpeaksFirstAfterIt() {
        // The previous answer's bubble closes at the boundary, so the next answer's first delta opens a FRESH
        // bubble instead of extending it — whichever of its text or audio arrives first.
        val t = Transcript().after(
            JarvisText("first answer", SPEAKING),
            ListeningStarted(0),
            JarvisText("second answer beginning", SPEAKING),
        )
        assertEquals(listOf(Role.MODEL to "first answer", Role.MODEL to "second answer beginning"), t.pairs())
    }

    @Test fun jarvisTextWhileListeningIsDropped() {
        // A belated word of the previous answer, after listening restarted, must not open a stray bubble.
        val listening = Transcript().after(UserText("hi"), JarvisText("hello", SPEAKING), ListeningStarted(0))
        assertSame(listening, listening.on(JarvisText(" there", Phase.LISTENING)))
        assertEquals(listOf(Role.USER to "hi", Role.MODEL to "hello"), listening.pairs())
    }

    @Test fun fullMultiTurnConversation() {
        val t = Transcript().after(
            ListeningStarted(0), UserText("weather?"),
            JarvisText("It's ", SPEAKING), JarvisText("sunny.", SPEAKING),
            ListeningStarted(0), UserText("tomorrow?"),
            JarvisText("Rain.", SPEAKING),
        )
        assertEquals(
            listOf(
                Role.USER to "weather?",
                Role.MODEL to "It's sunny.",
                Role.USER to "tomorrow?",
                Role.MODEL to "Rain.",
            ),
            t.pairs(),
        )
    }

    @Test fun jarvisMarkdownIsStrippedAcrossDeltas() {
        // Jarvis streams markdown; bold markers split across deltas ("**Es" + "presso:**") still strip.
        val t = Transcript().after(
            UserText("drinks?"),
            JarvisText("**Es", SPEAKING),
            JarvisText("presso:** the best", SPEAKING),
        )
        assertEquals(listOf(Role.USER to "drinks?", Role.MODEL to "Espresso: the best"), t.pairs())
    }

    @Test fun leadingListMarkerOpensNoBubbleUntilRealText() {
        // A leading "1. " strips to empty, so no empty bubble appears until real text arrives.
        val afterMarker = Transcript().after(JarvisText("1. ", SPEAKING))
        assertEquals(emptyList<Pair<Role, String>>(), afterMarker.pairs())
        val grown = afterMarker.on(JarvisText("Espresso\n2. Latte", SPEAKING))
        assertEquals(listOf(Role.MODEL to "Espresso\nLatte"), grown.pairs())
    }

    @Test fun userTextIsNotStripped() {
        // Only Jarvis's text is markdown; a user turn keeps its characters verbatim.
        val t = Transcript().after(UserText("what is 2 * 3?"))
        assertEquals(listOf(Role.USER to "what is 2 * 3?"), t.pairs())
    }

    @Test fun turnIdIsStableAcrossDeltas() {
        val first = Transcript().after(JarvisText("Let me ", SPEAKING))
        val grown = first.on(JarvisText("tell you", SPEAKING))
        assertEquals(first.turns.last().id, grown.turns.last().id)
        assertEquals("Let me tell you", grown.turns.last().text)
    }

    @Test fun isImmutable() {
        val base = Transcript().after(UserText("hi"))
        base.on(UserText(" there")) // discarded
        assertEquals(listOf(Role.USER to "hi"), base.pairs())
    }

    @Test fun emptyDeltasChangeNothing() {
        val t = Transcript().after(UserText("hi"))
        assertSame(t, t.on(UserText("")))
        assertSame(t, t.on(JarvisText("", SPEAKING)))
    }

    @Test fun resumeSeedKeepsTheShownTurnsAndAppendsAfterThem() {
        val shown = listOf(Turn(Role.USER, "weather?"), Turn(Role.MODEL, "Sunny."))
        val t = Transcript(turns = shown)
            .after(ListeningStarted(0), UserText("tomorrow?"), JarvisText("Rain.", SPEAKING))
        assertEquals(
            listOf(Role.USER to "weather?", Role.MODEL to "Sunny.", Role.USER to "tomorrow?", Role.MODEL to "Rain."),
            t.pairs(),
        )
        assertEquals(shown.map { it.id }, t.turns.take(2).map { it.id })
    }

    // ---- paced reveal --------------------------------------------------------------------------------

    /** Jarvis is answering [tenWords], listening having started with nothing played yet. */
    private val answering = Transcript().after(ListeningStarted(0), JarvisText(tenWords, SPEAKING))

    @Test fun nothingRevealedBeforeAnyAudio() {
        assertEquals(0, answering.latestRevealed())
        assertSame(answering, answering.on(AudioPlayed(50))) // no audio received yet → no fraction
    }

    @Test fun revealsInProportionToTheAudioPlayed() {
        val t = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(50)) // half played
        assertEquals(5, t.latestRevealed())
    }

    @Test fun neverUnrevealsWhenTheFractionDips() {
        val half = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(50)) // → 5
        val more = half.after(AudioReceived(100, playedBytes = 50), AudioPlayed(50)) // 50 of 200 → 0.25
        assertEquals(5, more.latestRevealed())
    }

    @Test fun capsAtTheAnswersWords() {
        val t = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(1_000)) // played runs past received
        assertEquals(10, t.latestRevealed())
    }

    @Test fun tracksGrowingTextMonotonically() {
        val t = Transcript().after(ListeningStarted(0), JarvisText("one two three four", SPEAKING))
            .after(AudioReceived(100, playedBytes = 0), AudioPlayed(50))
        assertEquals(2, t.latestRevealed()) // 4 words, half played

        val grown = t.after(
            JarvisText(" five six seven eight nine ten", SPEAKING),
            AudioReceived(100, playedBytes = 50),
            AudioPlayed(150),
        )
        assertEquals(8, grown.latestRevealed()) // 10 words, 0.75 played → round(7.5)
    }

    @Test fun audioBeforeTextCountsTowardsTheFreshTurnsReveal() {
        // Jarvis's audio can arrive before its first word: the bubble opens fresh on the text, and the reveal
        // counts that earlier audio.
        val t = Transcript().after(
            JarvisText("previous answer", SPEAKING),
            ListeningStarted(playedBytes = 400),
            AudioReceived(100, playedBytes = 400),
            JarvisText(tenWords, SPEAKING),
        )
        assertEquals(listOf(Role.MODEL to "previous answer", Role.MODEL to tenWords), t.pairs())
        assertEquals(0, t.latestRevealed())
        assertEquals(5, t.on(AudioPlayed(450)).latestRevealed())
    }

    @Test fun interruptionStartsAFreshTurnRevealedFromItsFirstWord() {
        val midAnswer = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(80)) // → 8
        val t = midAnswer.after(
            Interrupted(playedBytes = 80),
            JarvisText("Sure, here's another.", SPEAKING),
            AudioReceived(100, playedBytes = 80),
        )
        assertEquals(Role.MODEL to "Sure, here's another.", t.pairs().last())
        assertEquals(0, t.latestRevealed())
        assertEquals(2, t.on(AudioPlayed(130)).latestRevealed()) // half of 3 words → round(1.5)
    }

    // ---- a turn that ended early ---------------------------------------------------------------------

    @Test fun anInterruptedAnswerIsCutAtTheWordsHeard() {
        val t = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(80), Interrupted(playedBytes = 80))
        assertEquals(listOf(Role.MODEL to "one two three four five six seven eight"), t.pairs())
        assertEquals(null, t.latestRevealed())
    }

    @Test fun anAnswerFlushedBeforeListeningRestartsIsCutAtTheWordsHeard() {
        // A stalled turn flushes its unplayed audio, then listening restarts.
        val t = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(50), ListeningStarted(50))
        assertEquals(listOf(Role.MODEL to "one two three four five"), t.pairs())
    }

    @Test fun theCutCountsWordsThatArrivedAfterTheLastAudio() {
        val t = Transcript().after(
            ListeningStarted(0),
            JarvisText("one two", SPEAKING),
            AudioReceived(100, playedBytes = 0),
            AudioPlayed(50), // 1 of 2 words
            JarvisText(" three four five six seven eight nine ten", SPEAKING),
            Interrupted(playedBytes = 50),
        )
        assertEquals(listOf(Role.MODEL to "one two three four five"), t.pairs())
    }

    @Test fun anAnswerHeardInFullKeepsItsFullTextWhenInterrupted() {
        val t = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(100), Interrupted(playedBytes = 100))
        assertEquals(listOf(Role.MODEL to tenWords), t.pairs())
        assertEquals(null, t.latestRevealed())
    }

    @Test fun anAnswerCutBeforeAnyWordWasHeardIsRemoved() {
        val t = Transcript().after(
            ListeningStarted(0),
            UserText("tell me a story"),
            JarvisText(tenWords, SPEAKING),
            AudioReceived(100, playedBytes = 0),
            Interrupted(playedBytes = 0),
        )
        assertEquals(listOf(Role.USER to "tell me a story"), t.pairs())
    }

    @Test fun onlyTheActiveJarvisTurnCarriesARevealCount() {
        val revealed = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(100))
        val next = revealed.after(ListeningStarted(100), UserText("and?"), JarvisText("More.", SPEAKING))
        assertEquals(listOf(null, null, 0), next.turns.map { it.revealedWords })
    }

    @Test fun audioThatShowsNoNewWordKeepsTheSameTurns() {
        // Received audio is bookkeeping: it never shows a word on its own here (the fraction only dips), so the
        // engine has nothing to publish — the turns list is the same one.
        val half = answering.after(AudioReceived(100, playedBytes = 0), AudioPlayed(50))
        val more = half.on(AudioReceived(100, playedBytes = 50))
        assertSame(half.turns, more.turns)
        assertSame(more, more.on(AudioPlayed(50)))
    }

    private companion object {
        val SPEAKING = Phase.SPEAKING
    }
}
