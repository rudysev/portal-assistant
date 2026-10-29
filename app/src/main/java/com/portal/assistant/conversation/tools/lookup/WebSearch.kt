package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.conversation.Lookup
import com.portal.assistant.conversation.Markdown
import com.portal.assistant.conversation.tools.Tool
import com.portal.assistant.conversation.tools.ToolOutput
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * **Web search**, `portal.web_search`: everything live that isn't the weather (news, scores, prices, hours, recent
 * events), with the user's own Search key. Tavily `POST /search` behind [LookupHttp]: one basic search (1 credit)
 * for [MAX_RESULTS] results, the model's `topic` passed through. The result is
 * `{"sources":[{"title","snippet","published"?}]}`, sized for a voice model: no URLs, each snippet plain text of at
 * most [SNIPPET_MAX_CHARS], `published` in the device's zone when Tavily dated it. Never Tavily's answer: it garbles
 * facts the sources have right, so the model reads the sources itself. One request bounded by [TIMEOUT_MS]; every
 * failure is the tool's `{"error": …}`, so the model says it can't check. Pure: the Search key and zone are read
 * when the tool runs.
 */
class WebSearch(private val http: LookupHttp, private val zone: () -> ZoneId, private val searchKey: () -> String?) :
    Tool {

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
                .put("max_results", MAX_RESULTS)
                .toString(),
            timeoutMs = TIMEOUT_MS,
        )
        val response = http.execute(request)
        if (response !is LookupResponse.Body) return ToolOutput.error(checkNotNull(response.lookupError(LOOKUP)))
        return try {
            ToolOutput(result(JSONObject(response.text)))
        } catch (_: JSONException) {
            ToolOutput.error(unavailable(LOOKUP))
        }
    }

    /**
     * Each result's title, plain snippet and, when Tavily dated it, [published]; never a URL, never Tavily's answer.
     * No results reads as no sources.
     */
    private fun result(body: JSONObject): JSONObject {
        val results = body.getJSONArray("results")
        val zone = zone()
        val sources = JSONArray()
        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            sources.put(
                JSONObject()
                    .put("title", plain(result.getString("title")))
                    .put("snippet", snippet(result.getString("content")))
                    .apply { published(result.optString("published_date"), zone)?.let { put("published", it) } },
            )
        }
        return JSONObject().put("sources", sources)
    }

    companion object {
        /**
         * The one request's bound, connect to body. Measured on a Portal gen2 (2026-10-28): the slowest search took
         * 2.6 s (Tavily's own `response_time` at most 2.6 s of it), so 5 s leaves about twice that.
         */
        const val TIMEOUT_MS = 5_000L

        /** A snippet's longest, so five sources stay around 2k chars for the model. */
        const val SNIPPET_MAX_CHARS = 300

        private const val MAX_RESULTS = 5
        private const val SEARCH_URL = "https://api.tavily.com/search"
        private const val LOOKUP = "search"
        private const val ELLIPSIS = "…"
        private val TOPICS = setOf("general", "news", "finance")
        private val BARE_URL = Regex("""https?://\S+""")
        private val WHITESPACE = Regex("""\s+""")
        private val PUBLISHED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

        /** The model's topic when Tavily has it, else a general search. */
        private fun topic(asked: String): String =
            asked.trim().lowercase(Locale.ROOT).takeIf { it in TOPICS } ?: "general"

        /** Spoken-friendly text: markdown and bare URLs removed, whitespace runs folded to one space. */
        private fun plain(text: String): String =
            Markdown.strip(text).replace(BARE_URL, "").replace(WHITESPACE, " ").trim()

        /**
         * Tavily's `published_date` (RFC 1123, GMT) as `yyyy-MM-dd HH:mm` in [zone], the zone the prompt's time line
         * states, so "last night" matches; null when missing or unreadable.
         */
        private fun published(date: String, zone: ZoneId): String? = try {
            ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(zone)
                .format(PUBLISHED_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }

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
