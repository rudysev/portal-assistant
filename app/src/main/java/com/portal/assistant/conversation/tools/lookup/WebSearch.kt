package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.conversation.Lookup
import com.portal.assistant.conversation.Markdown
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Locale

/**
 * **Web search**, `portal.web_search`: everything live that isn't the weather (news, scores, prices, hours, recent
 * events), with the user's own Search key. Tavily `POST /search` behind [LookupHttp]: one basic search (1 credit)
 * for [MAX_RESULTS] results with Tavily's short answer, the model's `topic` passed through. The result is
 * `{"answer", "sources":[{"title","snippet"}]}`, sized for a voice model: no URLs, each snippet plain text of at
 * most [SNIPPET_MAX_CHARS]; with no answer, the sources alone; with an answer and no results, no sources. One
 * request bounded by [TIMEOUT_MS]; every failure is the tool's `{"error": …}`, so the model says it can't check.
 * Pure: the Search key is read when the tool runs.
 */
class WebSearch(private val http: LookupHttp, private val searchKey: () -> String?) : Tool {

    override val name: String = checkNotNull(Lookup.WEB_SEARCH.toolName)

    override val declaration: JSONObject = JSONObject(
        """{"name":"$name",
           "description":"Search the web for live info: news, scores, prices, opening hours, recent events.",
           "parameters":{"type":"OBJECT",
             "properties":{
               "query":{"type":"STRING","description":"What to search for, as a short search query."},
               "topic":{"type":"STRING","enum":["general","news","finance"],
                 "description":"news for current events, finance for markets and prices, else general."}},
             "required":["query"]}}""",
    )

    override fun invoke(args: JSONObject): ToolOutput {
        val query = args.optString("query").trim()
        val key = searchKey()?.trim()?.ifEmpty { null }
        return when {
            key == null -> ToolOutput.error("no search key")
            query.isEmpty() -> ToolOutput.error("no query")
            else -> search(query, topic(args.optString("topic")), key)
        }
    }

    private fun search(query: String, topic: String, key: String): ToolOutput {
        val request = LookupRequest(
            url = SEARCH_URL,
            headers = mapOf("Authorization" to "Bearer $key"),
            jsonBody = JSONObject()
                .put("query", query)
                .put("topic", topic)
                .put("search_depth", "basic")
                .put("include_answer", "basic")
                .put("max_results", MAX_RESULTS)
                .toString(),
            timeoutMs = TIMEOUT_MS,
        )
        return when (val response = http.execute(request)) {
            is LookupResponse.Body -> try {
                ToolOutput(result(JSONObject(response.text)))
            } catch (_: JSONException) {
                ToolOutput.error(UNAVAILABLE)
            }

            is LookupResponse.Status -> ToolOutput.error("$UNAVAILABLE (HTTP ${response.code})")

            LookupResponse.TimedOut -> ToolOutput.error(TIMED_OUT)

            is LookupResponse.Failure -> ToolOutput.error(UNAVAILABLE)
        }
    }

    /**
     * Tavily's answer, when it gave one, and each result's title and plain snippet; never a URL. An answer with no
     * results is still an answer (no sources); with neither, the body isn't one we can read.
     */
    private fun result(body: JSONObject): JSONObject {
        val answer = body.optString("answer").takeUnless { body.isNull("answer") }?.let(::plain)
        val results = if (body.isNull("results") &&
            !answer.isNullOrEmpty()
        ) {
            JSONArray()
        } else {
            body.getJSONArray("results")
        }
        val sources = JSONArray()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            sources.put(
                JSONObject()
                    .put("title", plain(result.getString("title")))
                    .put("snippet", snippet(result.getString("content"))),
            )
        }
        return JSONObject().apply { if (!answer.isNullOrEmpty()) put("answer", answer) }.put("sources", sources)
    }

    companion object {
        /** The one request's bound, connect to body. Provisional (mirrors the external tool bound). */
        const val TIMEOUT_MS = 5_000L

        /** A snippet's longest, so three sources stay around 1.5k chars for the model. */
        const val SNIPPET_MAX_CHARS = 300

        private const val MAX_RESULTS = 3
        private const val SEARCH_URL = "https://api.tavily.com/search"
        private const val UNAVAILABLE = "search unavailable"
        private const val TIMED_OUT = "search timed out"
        private const val ELLIPSIS = "…"
        private val TOPICS = setOf("general", "news", "finance")
        private val BARE_URL = Regex("""https?://\S+""")
        private val WHITESPACE = Regex("""\s+""")

        /** The model's topic when Tavily has it, else a general search. */
        private fun topic(asked: String): String =
            asked.trim().lowercase(Locale.ROOT).takeIf { it in TOPICS } ?: "general"

        /** Spoken-friendly text: markdown and bare URLs removed, whitespace runs folded to one space. */
        private fun plain(text: String): String =
            Markdown.strip(text).replace(BARE_URL, "").replace(WHITESPACE, " ").trim()

        /** [plain] text cut at a word to at most [SNIPPET_MAX_CHARS], marked with an ellipsis when cut. */
        private fun snippet(text: String): String {
            val clean = plain(text)
            if (clean.length <= SNIPPET_MAX_CHARS) return clean
            val cut = clean.take(SNIPPET_MAX_CHARS - ELLIPSIS.length)
            val atWord = cut.substringBeforeLast(' ', cut).trimEnd(' ', ',', ';', ':')
            return atWord + ELLIPSIS
        }
    }
}
