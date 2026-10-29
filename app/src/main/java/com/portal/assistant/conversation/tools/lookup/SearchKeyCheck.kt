package com.portal.assistant.conversation.tools.lookup

import com.portal.assistant.util.Http
import com.portal.assistant.util.KeyVerdict
import com.portal.commons.DebugLog
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection

/**
 * One-shot check of the user's Search key (BYOD — Settings → Search key) before it's stored, so a mistyped key
 * doesn't silently break Web search. A GET to Tavily's usage endpoint with the key as bearer auth: it proves the
 * key without spending a search credit. The status → [KeyVerdict] mapping is the pure, unit-tested [classify];
 * [validate] is the thin I/O around it (as [GeminiKeyCheck][com.portal.assistant.gemini.GeminiKeyCheck]).
 */
object SearchKeyCheck {

    private val INVALID_KEY_CODES = setOf(HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN)

    private const val USAGE_URL = "https://api.tavily.com/usage"

    /** Map an HTTP status to a verdict. 2xx = valid; 401/403 = invalid; anything else (a rate or plan limit —
     *  429/432/433 — a 5xx, or unexpected) is no verdict, so a throttled or spent key is never called wrong. Pure. */
    fun classify(code: Int): KeyVerdict = when {
        code in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE -> KeyVerdict.Valid
        code in INVALID_KEY_CODES -> KeyVerdict.Invalid
        else -> KeyVerdict.NetworkError
    }

    /** Blocking — call off the main thread. Checks [key] against the usage endpoint. A blank key is
     *  [KeyVerdict.Invalid] without a network round-trip. The key is never logged. */
    fun validate(key: String): KeyVerdict {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return KeyVerdict.Invalid
        val request = Request.Builder().url(USAGE_URL).header("Authorization", "Bearer $trimmed").build()
        return try {
            Http.shared.newCall(request).execute().use { resp ->
                DebugLog.log("search key check: HTTP ${resp.code}")
                classify(resp.code)
            }
        } catch (e: IOException) {
            DebugLog.log("search key check failed (network): ${e.javaClass.simpleName}")
            KeyVerdict.NetworkError
        } catch (e: IllegalArgumentException) {
            // A character OkHttp refuses in a header value (a pasted newline, a non-ASCII char): not a usable key.
            DebugLog.log("search key check: unusable key (${e.javaClass.simpleName})")
            KeyVerdict.Invalid
        }
    }
}
