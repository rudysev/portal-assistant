package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.conversation.tools.ToolOutput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests for **Web search** at the `Tool` seam, behind a fake [LookupHttp] answering with recorded Tavily
 * `/search` responses: each test calls the tool and pins the JSON the model reads, and the request only where it
 * encodes a decision (auth, topic, result count, depth).
 */
class WebSearchTest {

    private val http = FakeLookupHttp()
    private var searchKey: String? = KEY

    private fun invoke(args: JSONObject): ToolOutput = WebSearch(http.port, searchKey = { searchKey }).invoke(args)

    private fun search(query: String, topic: String? = null) =
        invoke(JSONObject().put("query", query).apply { if (topic != null) put("topic", topic) })

    @Test fun theAnswerAndEachSourcesTitleAndSnippetComeBackWithNoUrls() {
        http.onBody(SEARCH, RESPONSE_WITH_ANSWER)

        assertJson(
            """{"answer":"The Seattle Mariners beat the Houston Astros 5-3 on October 27, 2026.",
               "sources":[
                 {"title":"Mariners top Astros 5-3",
                  "snippet":"Cal Raleigh homered twice as Seattle beat Houston 5-3 on Tuesday night."},
                 {"title":"Astros vs. Mariners box score",
                  "snippet":"Final score: Mariners 5, Astros 3. Attendance 41,212."},
                 {"title":"MLB scores","snippet":"Tuesday's results across the league."}]}""",
            search("mariners score last night"),
        )
    }

    @Test fun oneBasicSearchForThreeResultsWithAnAnswerIsAskedForWithTheSearchKeyAsBearerAuth() {
        http.onBody(SEARCH, RESPONSE_WITH_ANSWER)

        search("  mariners score last night ")

        val request = http.requestsTo(SEARCH).single()
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        val body = JSONObject(checkNotNull(request.jsonBody))
        assertEquals("mariners score last night", body.getString("query"))
        assertEquals("basic", body.getString("search_depth"))
        assertEquals("basic", body.getString("include_answer"))
        assertEquals(3, body.getInt("max_results"))
        assertEquals(WebSearch.TIMEOUT_MS, request.timeoutMs)
    }

    @Test fun theTopicIsPassedThroughAndAnythingElseIsAGeneralSearch() {
        val cases = mapOf(
            "news" to "news",
            "finance" to "finance",
            "general" to "general",
            null to "general",
            "sports" to "general",
            "NEWS" to "news",
        )
        for ((topic, sent) in cases) {
            http.requests.clear()
            http.onBody(SEARCH, RESPONSE_WITH_ANSWER)

            search("anything", topic)

            assertEquals("topic=$topic", sent, JSONObject(checkNotNull(http.requests.single().jsonBody))["topic"])
        }
    }

    @Test fun withNoAnswerTheSourcesComeBackAlone() {
        http.onBody(SEARCH, RESPONSE_WITHOUT_ANSWER)

        assertJson(
            """{"sources":[{"title":"Pike Place Market hours","snippet":"Open daily 9 a.m. to 6 p.m."}]}""",
            search("pike place market hours"),
        )
    }

    @Test fun anAnswerWithNoResultsComesBackWithNoSources() {
        for (body in listOf(RESPONSE_ANSWER_ONLY, """{"answer":"Pike Place Market opens at 9 a.m."}""")) {
            http.requests.clear()
            http.onBody(SEARCH, body)

            assertJson(
                """{"answer":"Pike Place Market opens at 9 a.m.","sources":[]}""",
                search("pike place market hours"),
            )
        }
    }

    @Test fun aSnippetHasItsMarkdownAndLinksStrippedAndIsTrimmedToAboutThreeHundredChars() {
        http.onBody(SEARCH, RESPONSE_MARKDOWN_AND_LONG)

        val sources = search("aapl stock", topic = "finance").response.getJSONArray("sources")

        assertEquals(
            "Apple (AAPL) closed at 241.12, up 1.3%. See the full quote.",
            sources.getJSONObject(0).getString("snippet"),
        )
        val long = sources.getJSONObject(1).getString("snippet")
        assertEquals(true, long.length <= WebSearch.SNIPPET_MAX_CHARS)
        assertEquals(true, long.startsWith("Apple shares rose on Tuesday "))
        assertEquals(true, long.endsWith("…"))
        assertEquals(false, long.contains("  "))
        assertEquals(false, long.contains("http"))
    }

    @Test fun anHttpErrorIsAnError() {
        http.on(
            SEARCH,
            LookupResponse.Status(432, """{"detail":{"error":"This request exceeds your plan's limit."}}"""),
        )

        assertJson("""{"error":"search unavailable (HTTP 432)"}""", search("anything"))
    }

    @Test fun aMalformedBodyIsAnError() {
        for (body in listOf("<html>Bad gateway</html>", """{"answer":null}""", "")) {
            http.requests.clear()
            http.onBody(SEARCH, body)
            assertJson("""{"error":"search unavailable"}""", search("anything"))
        }
    }

    @Test fun noConnectionIsAnError() {
        http.on(SEARCH, LookupResponse.Failure("Unable to resolve host"))

        assertJson("""{"error":"search unavailable"}""", search("anything"))
    }

    @Test fun aTimeoutReportedByThePortIsAnError() {
        http.on(SEARCH, LookupResponse.TimedOut)

        assertJson("""{"error":"search timed out"}""", search("anything"))
    }

    @Test fun noSearchKeyIsAnErrorAndNothingIsSent() {
        searchKey = null

        assertJson("""{"error":"no search key"}""", search("anything"))
        assertEquals(emptyList<LookupRequest>(), http.requests)
    }

    @Test fun anEmptyQueryIsAnErrorAndNothingIsSent() {
        assertJson("""{"error":"no query"}""", invoke(JSONObject().put("query", "  ")))
        assertEquals(emptyList<LookupRequest>(), http.requests)
    }

    // --- helpers ---

    /** The model reads [expected] exactly: same keys and values (key order isn't part of JSON). */
    private fun assertJson(expected: String, output: ToolOutput) =
        assertEquals(JSONObject(expected).plain(), output.response.plain())

    private fun Any?.plain(): Any? = when (this) {
        is JSONObject -> keys().asSequence().associateWith { get(it).plain() }
        is JSONArray -> (0 until length()).map { get(it).plain() }
        is Number -> toDouble()
        else -> this
    }

    private companion object {
        const val SEARCH = "https://api.tavily.com/search"
        const val KEY = "tvly-dev-abc123"

        // Recorded from POST api.tavily.com/search (include_answer=basic, search_depth=basic, max_results=3).
        const val RESPONSE_WITH_ANSWER = """{"query":"mariners score last night",
            "answer":"The Seattle Mariners beat the Houston Astros 5-3 on October 27, 2026.",
            "images":[],
            "results":[
              {"url":"https://www.mlb.com/news/mariners-astros-recap","title":"Mariners top Astros 5-3",
               "content":"Cal Raleigh homered twice as Seattle beat Houston 5-3 on Tuesday night.",
               "score":0.91,"raw_content":null,"id":"r1"},
              {"url":"https://www.espn.com/mlb/boxscore","title":"Astros vs. Mariners box score",
               "content":"Final score: Mariners 5, Astros 3. Attendance 41,212.","score":0.84,"raw_content":null,
               "id":"r2"},
              {"url":"https://www.cbssports.com/mlb/scoreboard/","title":"MLB scores",
               "content":"Tuesday's results across the league.","score":0.62,"raw_content":null,"id":"r3"}],
            "response_time":1.42,"usage":{"credits":1},"request_id":"5c1e7f9a-2b4d-4e11-9a0f-3d6b8c2e1f40"}"""

        // The same request where Tavily gave no answer: "answer" is null.
        const val RESPONSE_WITHOUT_ANSWER = """{"query":"pike place market hours","answer":null,"images":[],
            "results":[
              {"url":"https://www.pikeplacemarket.org/hours","title":"Pike Place Market hours",
               "content":"Open daily 9 a.m. to 6 p.m.","score":0.88,"raw_content":null,"id":"r1"}],
            "response_time":0.97,"usage":{"credits":1},"request_id":"0b7e2d14-6c3a-4f58-8e9d-1a2b3c4d5e6f"}"""

        // Tavily gave an answer but no results.
        const val RESPONSE_ANSWER_ONLY = """{"query":"pike place market hours",
            "answer":"Pike Place Market opens at 9 a.m.","images":[],"results":[],
            "response_time":0.81,"usage":{"credits":1},"request_id":"2d4f6a8c-1e3b-4d5f-9a7c-6b8e0f2a4c6e"}"""

        // topic=finance: a snippet carrying markdown (bold, a link, a heading) and one far over the snippet limit.
        const val RESPONSE_MARKDOWN_AND_LONG = """{"query":"aapl stock","answer":"","images":[],
            "results":[
              {"url":"https://finance.yahoo.com/quote/AAPL","title":"Apple Inc. (AAPL)",
               "content":"## Apple (AAPL)\n**closed at 241.12**, up 1.3%. """ +
            """See [the full quote](https://finance.yahoo.com/quote/AAPL).",
               "score":0.93,"raw_content":null,"id":"r1"},
              {"url":"https://www.reuters.com/markets/apple","title":"Apple shares rise",
               "content":"Apple shares rose on Tuesday after the company reported record services revenue """ +
            """for the quarter, beating analyst estimates.\n\nThe stock gained 1.3% to close at 241.12, its """ +
            """highest close since July. Investors focused on the services segment, which grew 14% from a year """ +
            """earlier, and on guidance for the holiday quarter that came in above expectations. More at """ +
            """https://www.reuters.com/markets/apple and in our live coverage of the earnings call, where """ +
            """executives took questions for over an hour.",
               "score":0.81,"raw_content":null,"id":"r2"}],
            "response_time":1.88,"usage":{"credits":1},"request_id":"9f8e7d6c-5b4a-4321-8765-0fedcba98765"}"""
    }
}
