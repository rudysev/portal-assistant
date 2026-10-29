package com.portal.assistant.gemini

import com.portal.assistant.util.Http
import com.portal.assistant.util.KeyVerdict
import com.portal.commons.DebugLog
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.HttpURLConnection

/**
 * One-shot validation of a user-supplied Gemini API key (BYOD — Settings → API key). The Live API is a
 * WebSocket, so a key is otherwise only proven when a conversation connects; this lets Settings confirm a
 * pasted key *before* storing it, so a non-technical user gets instant feedback on a typo or a disabled key.
 *
 * A cheap, free GET to the Generative Language REST endpoint (same host as the Live API): listing models
 * needs no request body and no token spend. The status-code → [KeyVerdict] mapping is the pure, unit-tested
 * [classify]; [validate] is the thin I/O around it (mirrors the codebase's pure-core + thin-shell pattern).
 */
object GeminiKeyCheck {

    private val INVALID_KEY_CODES = setOf(
        HttpURLConnection.HTTP_BAD_REQUEST,
        HttpURLConnection.HTTP_UNAUTHORIZED,
        HttpURLConnection.HTTP_FORBIDDEN,
    )

    private const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    /** Map an HTTP status to a verdict. 2xx = valid; auth/bad-request = invalid; anything else (5xx,
     *  unexpected) is treated as a transient error so a flaky server never discards a good key. Pure. */
    fun classify(code: Int): KeyVerdict = when {
        code in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE -> KeyVerdict.Valid
        code in INVALID_KEY_CODES -> KeyVerdict.Invalid
        else -> KeyVerdict.NetworkError
    }

    /** Blocking — call off the main thread. Validates [key] against the models endpoint. A blank key is
     *  [KeyVerdict.Invalid] without a network round-trip. */
    fun validate(key: String): KeyVerdict {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return KeyVerdict.Invalid
        // addQueryParameter percent-encodes the key, so a stray reserved char can't corrupt the request
        // (and misclassify a good key as Invalid).
        val url = MODELS_URL.toHttpUrl().newBuilder().addQueryParameter("key", trimmed).build()
        val request = Request.Builder().url(url).build()
        return try {
            Http.shared.newCall(request).execute().use { resp ->
                DebugLog.log("api key check: HTTP ${resp.code}")
                classify(resp.code)
            }
        } catch (e: IOException) {
            DebugLog.log("api key check failed (network): ${e.javaClass.simpleName}")
            KeyVerdict.NetworkError
        }
    }
}
